# Publiczny serwer GPSChat

Wymagane: serwer z Dockerem i Docker Compose, domena wskazująca jego IP oraz
otwarte porty 80/443. Kopiuj projekt na serwer. Nie wystawiaj portu API 8080
bezpośrednio do Internetu.

W katalogu `deploy/` ustaw w środowisku uruchomieniowym:

- `GPSCHAT_DOMAIN`: rzeczywista domena API, bez `https://`, portu i ścieżki.
- `GPSCHAT_ADMIN_TOKEN`: silny, losowy sekret moderatora. Wygeneruj go np.
  poleceniem `python3 -c 'import secrets; print(secrets.token_hex(32))'` i zachowaj
  w bezpiecznym magazynie hostingu. Nie umieszczaj go w APK ani repozytorium.

```bash
docker compose up -d --build
docker compose ps
```

Caddy pozyska certyfikat HTTPS. Sprawdź `/health` przez HTTPS i wpisz adres
`https://twoja-domena` w Ustawieniach aplikacji. Przetestuj publikację i odczyt
na dwóch telefonach, zanim udostępnisz APK.

Dołączona konfiguracja jest punktem startowym dla małego wdrożenia: API używa
Python ThreadingHTTPServer i SQLite. Nie była sprawdzana pod dużym obciążeniem.
Dla szerszej usługi dobierz procesy, limity połączeń i monitoring. Nie włączaj
logowania URL-i pobrań z lokalizacją ani nagłówków Authorization.

## Moderacja

API administratora wymaga `Authorization: Bearer <GPSCHAT_ADMIN_TOKEN>`:

- `GET /admin/reports` — lista zgłoszonych wpisów.
- `DELETE /admin/messages/<id>` — ukrycie wpisu przed odczytem i alertami.

Nie ma jeszcze graficznego panelu moderatora. Wpis nie jest automatycznie
usuwany po zgłoszeniu; operator musi reagować na zgłoszenia. Sekret administratora
jest niezależny od losowych kluczy instalacji użytkowników.

## Trwałość i kopie

Wolumen `messages` zawiera bazę `/data/gpschat.sqlite3`. `docker compose down`
zachowuje bazę; **`down -v` ją usuwa**. Wykonuj kopie przy użyciu API backup
SQLite lub po bezpiecznym zatrzymaniu API, uwzględniając pliki WAL. Sprawdź
odtworzenie kopii przed rozpoczęciem produkcyjnego używania.

Pole `GPSCHAT_TRUSTED_PROXIES` służy do limitów według faktycznego IP użytkownika.
Konfiguracja Compose zakłada, że API jest dostępne tylko w prywatnej sieci Dockera,
a Caddy nadpisuje `X-Real-IP`. Przy innym wdrożeniu zawęź listę do adresu proxy;
nie ufaj temu nagłówkowi od dowolnego klienta.
