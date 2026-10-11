import concurrent.futures
import json
import os
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest.mock import patch

from server import make_server, distance_m


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = make_server(port=0, database=self.temp.name + '/test.sqlite3')
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f'http://127.0.0.1:{self.server.server_port}'
        self.owner = 'a' * 64
        self.other = 'b' * 64

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, method, path, data=None, token=None):
        request = urllib.request.Request(self.base + path, method=method,
                                        data=json.dumps(data).encode() if data is not None else None,
                                        headers={'Authorization': 'Bearer ' + (token or self.owner),
                                                 'Content-Type': 'application/json'})
        try:
            response = urllib.request.urlopen(request, timeout=5)
        except urllib.error.HTTPError as e:
            response = e
        with response:
            return response.status, json.load(response)

    def create(self, text='Wiadomość na spacer', lat=52.2297, lon=21.0122, token=None):
        return self.request('POST', '/messages', {'text': text, 'latitude': lat, 'longitude': lon}, token)

    def test_two_installations_share_messages_and_ownership(self):
        status, message = self.create()
        self.assertEqual(status, 201)
        self.assertTrue(message['mine'])
        self.assertEqual(message['radius_m'], 100)
        self.assertEqual(message['expires_at'] - message['created_at'], 7 * 86400)
        status, body = self.request('GET', '/messages?lat=52.2297&lon=21.0122', token=self.other)
        self.assertEqual(status, 200)
        self.assertEqual(body['messages'][0]['text'], 'Wiadomość na spacer')
        self.assertFalse(body['messages'][0]['mine'])
        self.assertNotIn('owner', body['messages'][0])
        self.assertEqual(self.request('DELETE', '/messages/' + message['id'], token=self.other)[0], 403)
        self.assertEqual(self.request('DELETE', '/messages/' + message['id'])[0], 200)
        self.assertEqual(self.request('GET', '/messages?lat=52.2297&lon=21.0122')[1]['messages'], [])

    def test_location_filtering_and_date_line(self):
        self.create(lat=52.2297, lon=21.0122)
        self.assertEqual(self.request('GET', '/messages?lat=50&lon=20')[1]['messages'], [])
        self.create('Przy południku', lat=0, lon=179.99)
        found = self.request('GET', '/messages?lat=0&lon=-179.99')[1]['messages']
        self.assertEqual(len(found), 1)
        self.assertLess(distance_m(0, 179.99, 0, -179.99), 2500)

    def test_invalid_inputs_and_missing_identity(self):
        for lat, lon in [(91, 1), (1, 181), ('NaN', 0), (True, 0), (None, 0)]:
            self.assertEqual(self.create(lat=lat, lon=lon)[0], 400)
        for text in ['', '   ', 'x' * 501, 123]:
            self.assertEqual(self.create(text=text)[0], 400)
        self.assertEqual(self.request('GET', '/messages?lat=foo&lon=0')[0], 400)
        self.assertEqual(self.request('GET', '/messages?lat=0&lon=0', token='bad')[0], 401)

    def test_expired_messages_are_removed(self):
        _, m = self.create()
        with self.server.store.connect() as db:
            db.execute('UPDATE messages SET expires_at=? WHERE id=?', (int(time.time()) - 1, m['id']))
        self.assertEqual(self.request('GET', '/messages?lat=52.2297&lon=21.0122')[1]['messages'], [])
        self.assertEqual(self.request('DELETE', '/messages/' + m['id'])[0], 404)

    def test_report_is_idempotent_and_admin_can_hide(self):
        _, m = self.create()
        for _ in range(2):
            self.assertEqual(self.request('POST', '/messages/' + m['id'] + '/report', {}, self.other)[0], 200)
        self.assertEqual(self.request('GET', '/admin/reports')[0], 403)
        with patch.dict(os.environ, {'GPSCHAT_ADMIN_TOKEN': 'test-admin-secret'}):
            status, body = self.request('GET', '/admin/reports', token='test-admin-secret')
            self.assertEqual(status, 200)
            self.assertEqual(body['messages'][0]['reports'], 1)
            self.assertEqual(self.request('DELETE', '/admin/messages/' + m['id'], token='test-admin-secret')[0], 200)
        self.assertEqual(self.request('GET', '/messages?lat=52.2297&lon=21.0122')[1]['messages'], [])

    def test_post_rate_limit_survives_store_and_is_atomic(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            outcomes = list(pool.map(lambda _: self.create()[0], range(12)))
        self.assertEqual(outcomes.count(201), 10)
        self.assertEqual(outcomes.count(429), 2)
        self.assertEqual(self.create()[0], 429)

    def test_health_and_restart_persistence(self):
        self.assertEqual(self.request('GET', '/health')[1], {'status': 'ok'})
        self.create()
        from server import Store
        another = Store(self.temp.name + '/test.sqlite3')
        self.assertEqual(len(another.nearby(52.2297, 21.0122, '')), 1)

    def test_arrival_lookup_excludes_deleted_and_expired_content(self):
        _, active = self.create('Aktywna')
        _, deleted = self.create('Usunięta')
        _, expired = self.create('Wygasła')
        self.request('DELETE', '/messages/' + deleted['id'])
        with self.server.store.connect() as db:
            db.execute('UPDATE messages SET expires_at=0 WHERE id=?', (expired['id'],))
        status, body = self.request('POST', '/messages/lookup', {'ids': [active['id'], deleted['id'], expired['id']]}, self.other)
        self.assertEqual(status, 200)
        self.assertEqual([m['id'] for m in body['messages']], [active['id']])
        self.assertFalse(body['messages'][0]['mine'])
        self.assertEqual(self.request('POST', '/messages/lookup', {'ids': []})[0], 400)


if __name__ == '__main__':
    unittest.main()
