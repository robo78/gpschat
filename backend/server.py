"""GPSChat anonymous public API. Python 3.12+, no third-party dependencies.

Run behind an HTTPS reverse proxy for public use. No location history is stored.
"""
import argparse
import hashlib
import hmac
import ipaddress
import json
import logging
import math
import os
import re
import sqlite3
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

MAX_TEXT = 500
TTL = 7 * 86400
TOKEN = re.compile(r"^[a-f0-9]{64}$")


def distance_m(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    a = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 6371000 * 2 * math.asin(min(1, math.sqrt(a)))


class ApiError(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


class Store:
    def __init__(self, path):
        self.path = str(path)
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS messages (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, text TEXT NOT NULL,
                    latitude REAL NOT NULL, longitude REAL NOT NULL,
                    created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
                    hidden INTEGER NOT NULL DEFAULT 0
                );
                CREATE INDEX IF NOT EXISTS message_area ON messages(latitude, longitude);
                CREATE TABLE IF NOT EXISTS reports (
                    message_id TEXT NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
                    reporter TEXT NOT NULL, created_at INTEGER NOT NULL,
                    PRIMARY KEY(message_id, reporter)
                );
                CREATE TABLE IF NOT EXISTS limits (
                    key TEXT PRIMARY KEY, count INTEGER NOT NULL, until INTEGER NOT NULL
                );
            """)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        return db

    def limit(self, db, key, maximum, period=3600):
        now = int(time.time())
        row = db.execute("SELECT count, until FROM limits WHERE key=?", (key,)).fetchone()
        if row and row['until'] > now and row['count'] >= maximum:
            raise ApiError(429, "Zbyt wiele operacji. Spróbuj później.")
        count, until = (row['count'] + 1, row['until']) if row and row['until'] > now else (1, now + period)
        db.execute("INSERT OR REPLACE INTO limits VALUES (?, ?, ?)", (key, count, until))

    def clean(self, db):
        now = int(time.time())
        db.execute("DELETE FROM messages WHERE expires_at<=?", (now,))
        db.execute("DELETE FROM limits WHERE until<=?", (now,))

    @staticmethod
    def public(row, owner):
        return {k: row[k] for k in ('id', 'text', 'latitude', 'longitude', 'created_at', 'expires_at')} | {
            'radius_m': 100, 'mine': row['owner'] == owner
        }

    def nearby(self, lat, lon, owner):
        with self.connect() as db:
            self.clean(db)
            # Longitude is filtered with Haversine below, including the date line.
            rows = db.execute("SELECT * FROM messages WHERE hidden=0 AND latitude BETWEEN ? AND ?", (lat - .1, lat + .1)).fetchall()
        nearby = [(distance_m(lat, lon, r['latitude'], r['longitude']), r) for r in rows]
        return [self.public(row, owner) for dist, row in sorted(nearby, key=lambda p: p[0]) if dist <= 10000][:100]

    def lookup(self, ids, owner):
        if not isinstance(ids, list) or not 1 <= len(ids) <= 100 or any(not isinstance(i, str) or not re.fullmatch(r'[a-f0-9-]{36}', i) for i in ids):
            raise ApiError(400, 'Nieprawidłowa lista wiadomości.')
        with self.connect() as db:
            self.clean(db)
            placeholders = ','.join('?' for _ in ids)
            rows = db.execute(f'SELECT * FROM messages WHERE hidden=0 AND id IN ({placeholders})', ids).fetchall()
        return [self.public(r, owner) for r in rows]

    def create(self, data, owner, ip):
        text = data.get('text')
        if not isinstance(text, str) or not 1 <= len(text.strip()) <= MAX_TEXT:
            raise ApiError(400, "Wiadomość musi mieć od 1 do 500 znaków.")
        lat, lon = coordinates(data.get('latitude'), data.get('longitude'))
        now = int(time.time())
        mid = str(uuid.uuid4())
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self.clean(db)
            self.limit(db, 'post:owner:' + owner, 10)
            self.limit(db, 'post:ip:' + ip, 30)
            db.execute("INSERT INTO messages(id,owner,text,latitude,longitude,created_at,expires_at) VALUES (?,?,?,?,?,?,?)",
                       (mid, owner, text.strip(), lat, lon, now, now + TTL))
            row = db.execute("SELECT * FROM messages WHERE id=?", (mid,)).fetchone()
        return self.public(row, owner)

    def delete(self, mid, owner):
        with self.connect() as db:
            row = db.execute("SELECT owner FROM messages WHERE id=?", (mid,)).fetchone()
            if row is None:
                raise ApiError(404, "Wiadomość nie istnieje.")
            if row['owner'] != owner:
                raise ApiError(403, "Możesz usunąć tylko własną wiadomość.")
            db.execute("DELETE FROM messages WHERE id=?", (mid,))

    def report(self, mid, owner, ip):
        with self.connect() as db:
            db.execute("BEGIN IMMEDIATE")
            self.clean(db)
            if not db.execute("SELECT 1 FROM messages WHERE id=? AND hidden=0", (mid,)).fetchone():
                raise ApiError(404, "Wiadomość nie istnieje.")
            if db.execute("SELECT 1 FROM reports WHERE message_id=? AND reporter=?", (mid, owner)).fetchone():
                return
            self.limit(db, 'report:owner:' + owner, 20)
            self.limit(db, 'report:ip:' + ip, 60)
            db.execute("INSERT INTO reports VALUES (?, ?, ?)", (mid, owner, int(time.time())))


def coordinates(lat, lon):
    try:
        if isinstance(lat, bool) or isinstance(lon, bool):
            raise ValueError()
        lat, lon = float(lat), float(lon)
        if not math.isfinite(lat) or not math.isfinite(lon) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise ValueError()
        return lat, lon
    except (TypeError, ValueError):
        raise ApiError(400, "Nieprawidłowe współrzędne.")


class Handler(BaseHTTPRequestHandler):
    server_version = 'GPSChat/0.1'

    def log_message(self, fmt, *args):
        # URLs include location and must not be logged.
        logging.info('%s %s', self.command, args[1] if len(args) > 1 else 'request')

    def reply(self, status, data):
        body = json.dumps(data, ensure_ascii=False, allow_nan=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.end_headers()
        self.wfile.write(body)

    def identity(self):
        auth = self.headers.get('Authorization', '')
        token = auth.removeprefix('Bearer ')
        if not auth.startswith('Bearer ') or not TOKEN.fullmatch(token):
            raise ApiError(401, 'Brak identyfikatora instalacji.')
        return hashlib.sha256(token.encode()).hexdigest()

    def client_hash(self):
        address = self.client_address[0]
        # Only trust a header from an explicitly configured reverse proxy.
        networks = os.environ.get('GPSCHAT_TRUSTED_PROXIES', '').split(',')
        try:
            if any(ipaddress.ip_address(address) in ipaddress.ip_network(n.strip()) for n in networks if n.strip()):
                address = str(ipaddress.ip_address(self.headers.get('X-Real-IP', address)))
        except ValueError:
            pass
        return hashlib.sha256(address.encode()).hexdigest()

    def body(self):
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 8192:
                raise ApiError(413, 'Zbyt duże lub puste żądanie.')
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict):
                raise ValueError()
            return data
        except (ValueError, UnicodeDecodeError):
            raise ApiError(400, 'Nieprawidłowy JSON.')

    def dispatch(self):
        try:
            parsed = urlparse(self.path)
            path = parsed.path
            store = self.server.store
            if self.command == 'GET' and path == '/health':
                with store.connect() as db:
                    db.execute('SELECT 1').fetchone()
                return self.reply(200, {'status': 'ok'})
            if path.startswith('/admin/'):
                expected = os.environ.get('GPSCHAT_ADMIN_TOKEN', '')
                if not expected or not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + expected):
                    raise ApiError(403, 'Brak dostępu administratora.')
                if self.command == 'GET' and path == '/admin/reports':
                    with store.connect() as db:
                        rows = db.execute('SELECT m.id,m.text,m.hidden,COUNT(r.reporter) AS reports FROM messages m JOIN reports r ON r.message_id=m.id GROUP BY m.id ORDER BY reports DESC').fetchall()
                    return self.reply(200, {'messages': [dict(r) for r in rows]})
                match = re.fullmatch(r'/admin/messages/([a-f0-9-]{36})', path)
                if match and self.command == 'DELETE':
                    with store.connect() as db:
                        if not db.execute('UPDATE messages SET hidden=1 WHERE id=?', (match[1],)).rowcount:
                            raise ApiError(404, 'Wiadomość nie istnieje.')
                    return self.reply(200, {'status': 'hidden'})
                raise ApiError(404, 'Nieznana operacja.')
            owner = self.identity()
            ip = self.client_hash()
            if self.command == 'GET' or path == '/messages/lookup':
                with store.connect() as db:
                    db.execute('BEGIN IMMEDIATE')
                    store.limit(db, 'read:ip:' + ip, 240, 60)
            if self.command == 'GET' and path == '/messages':
                query = parse_qs(parsed.query)
                lat, lon = coordinates(query.get('lat', [None])[0], query.get('lon', [None])[0])
                return self.reply(200, {'messages': store.nearby(lat, lon, owner)})
            if self.command == 'POST' and path == '/messages':
                return self.reply(201, store.create(self.body(), owner, ip))
            if self.command == 'POST' and path == '/messages/lookup':
                return self.reply(200, {'messages': store.lookup(self.body().get('ids'), owner)})
            match = re.fullmatch(r'/messages/([a-f0-9-]{36})(/report)?', path)
            if match:
                if self.command == 'DELETE' and not match[2]:
                    store.delete(match[1], owner)
                    return self.reply(200, {'status': 'deleted'})
                if self.command == 'POST' and match[2]:
                    store.report(match[1], owner, ip)
                    return self.reply(200, {'status': 'reported'})
            raise ApiError(404, 'Nieznana operacja.')
        except ApiError as e:
            self.reply(e.status, {'error': e.message})
        except (sqlite3.Error, OSError):
            logging.exception('Storage error')
            self.reply(503, {'error': 'Serwer jest chwilowo niedostępny.'})

    do_GET = dispatch
    do_POST = dispatch
    do_DELETE = dispatch

    def setup(self):
        super().setup()
        self.connection.settimeout(15)


def make_server(host='127.0.0.1', port=8080, database='backend/data/gpschat.sqlite3'):
    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    server.store = Store(database)
    return server


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--database', default='backend/data/gpschat.sqlite3')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format='%(levelname)s %(message)s')
    server = make_server(args.host, args.port, args.database)
    print(f'GPSChat API listening on {args.host}:{args.port}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
