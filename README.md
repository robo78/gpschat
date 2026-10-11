# GPSChat — wiadomości zostawiane w miejscach

Aplikacja na Androida w Kotlinie i Jetpack Compose. Bez rejestracji i logowania.
Wiadomości są publiczne, przypisane do miejsca publikacji i wygasają po 7 dniach.
Promień alertu: 100 m. Mapa: OpenStreetMap. Wspólna baza: dołączone API Python + SQLite.

## Co zawiera projekt

- `app/`: mapa, lista wpisów, publikacja z aktualną lokalizacją, usuwanie własnych
  wpisów, zgłoszenia, ekran uprawnień, geofencing i powiadomienia.
- `backend/`: anonimowe API, trwała baza, limity publikacji i panel API moderatora.
- `deploy/`: Docker Compose z Caddy i automatycznym HTTPS.
- `scripts/`: instalacja narzędzi i polecenia dla tego środowiska chmurowego.

Nie ma kont użytkowników. Aplikacja generuje losowy 256-bitowy klucz instalacji,
przechowuje go prywatnie na telefonie i wysyła do wybranego API. Serwer zapisuje
tylko jego hash. Klucz pozwala usuwać własne wpisy; odinstalowanie aplikacji lub
wyczyszczenie jej danych odbiera tę możliwość. Kopie zapasowe danych aplikacji
są wyłączone. Wiadomości nadal wygasają automatycznie.

## Uruchomienie API lokalnie

Wymagany Python 3.12+. Nie potrzeba dodatkowych bibliotek.

```bash
cd /workspace/gpschat
python3 backend/server.py --host 0.0.0.0 --port 8080
```

W aplikacji otwórz Ustawienia, wpisz adres API i wybierz „Sprawdź i zapisz”.

- Emulator Androida na tym samym komputerze: `http://10.0.2.2:8080`.
- Telefon podłączony przez USB: `adb reverse tcp:8080 tcp:8080`, adres
  `http://127.0.0.1:8080` w aplikacji.
- Dwa telefony w tej samej sieci Wi-Fi: adres LAN komputera, np.
  `http://192.168.1.10:8080`; dopuść port 8080 w lokalnym firewallu.

Adresy te dotyczą komputera, na którym faktycznie uruchamiasz API. Lokalnego
API w środowisku chmurowym nie można po prostu otworzyć na swoim telefonie przez
`localhost`. Dla telefonu poza tą siecią potrzebny jest publiczny serwer HTTPS.
HTTP jest dozwolone tylko w APK debug do testów lokalnych. Nie używaj go w
otwartej sieci: treść, lokalizacja i klucz instalacji nie są wtedy szyfrowane.

## Budowanie APK

Otwórz projekt w Android Studio, zainstaluj SDK 35 i użyj JDK 17 lub 21.
Gradle Wrapper ma przypięty checksum oficjalnej dystrybucji Gradle 8.9.
Testy integracji klienta Kotlin wymagają także Pythona 3.12+ dostępnego jako
`python3` w PATH; uruchamiają własne API i tymczasową bazę, niezależnie od serwera
używanego na telefonie.

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Na maszynie chmurowej:

```bash
python3 scripts/cloud_setup.py
bash scripts/gradle-cloud.sh assembleDebug testDebugUnitTest lintDebug
python3 -m unittest discover -s backend -v
```

APK testowe znajduje się w `app/build/outputs/apk/debug/app-debug.apk`.
Wersja release wymusza HTTPS; przed dystrybucją wymaga własnego podpisu.

## Pierwsze użycie

1. Połącz aplikację z API w Ustawieniach.
2. Zezwól na **dokładną lokalizację**; włącz GPS w telefonie.
3. Zostaw wiadomość. Miejsce pobierane jest ponownie podczas publikacji;
   aplikacja odrzuca lokalizację starszą niż 2 minuty lub z dokładnością gorszą
   niż 100 m. Nie można przypinać wiadomości w dowolnym odległym miejscu przez UI.
4. Aby otrzymywać alerty, zezwól na powiadomienia, ustaw lokalizację
   **„Zezwalaj cały czas”** i włącz przełącznik „Alerty w pobliżu”.
5. Drugi telefon korzystający z tego samego API może odczytać wpis i dostać alert
   po wejściu w jego okolicę. Własne wpisy nie wywołują alertów.

## Jak działają alerty i ich ograniczenia

- Wymagane Android 8+ i usługi Google Play. Geofencing wykrywa wejście w obszar;
  aplikacja nie prowadzi ciągłego śledzenia GPS ani nie pokazuje innych osób.
- Rejestrowanych jest maksymalnie 100 najbliższych aktywnych, nieodczytanych
  przez alert wpisów, pobranych z obszaru do 10 km. Po aktywacji alertów również
  wpis znajdujący się już pod telefonem może wywołać pierwsze powiadomienie.
- Pobranie odbywa się przy otwarciu/odświeżeniu oraz przez WorkManager co
  **co najmniej** 15 minut. Zadanie w tle używa ostatniej lokalizacji nie starszej
  niż 30 minut. Nie jest to gwarancja sprawdzenia wszystkich miejsc podczas
  długiej podróży: warto ponownie otworzyć aplikację w nowej okolicy.
- Android, GPS, oszczędzanie baterii i producent telefonu mogą opóźniać zadania
  oraz geofencing. Nie można obiecać alertu dokładnie w chwili przekroczenia 100 m.
- Przed alertem zadanie pobiera aktualny wpis z API: usunięte, wygasłe lub ukryte
  wiadomości nie wywołują powiadomień. Potrzebne jest połączenie z Internetem.
  Bez sieci dostarczenie może nastąpić później.
- Po dostarczeniu zapisuje się identyfikator wiadomości. Ten sam wpis nie
  powiadamia ponownie w obrębie instalacji. Po reinstalacji historia jest nowa.
- Po restarcie telefonu aplikacja planuje ponowne pobranie. Gdy ostatnia
  lokalizacja jest niedostępna, otwórz ją ponownie. Wymuszone zatrzymanie przez
  ustawienia Androida blokuje alerty do ponownego uruchomienia.

## Wdrożenie wspólnego serwera

Instrukcja: [deploy/README.md](deploy/README.md). Nie wdrożono publicznego serwera
automatycznie. Hosting, domena i operator moderacji są potrzebne do udostępnienia
aplikacji wszystkim. Sposób uruchomienia nie wymaga Firebase ani klucza do map.

Limity: 10 publikacji na instalację/godzinę, 30 na adres IP/godzinę, 20 zgłoszeń
na instalację/godzinę, 60 na IP/godzinę, 240 odczytów/minutę/IP. W sieciach
współdzielących IP limity są wspólne. Anonimowe klucze nie są silnym potwierdzeniem
tożsamości ani zabezpieczeniem przed spoofingiem GPS. Przed szerszym wydaniem
potrzebne są testy obciążenia i aktywna moderacja.

## Prywatność

Treść i współrzędne publikacji są publiczne. Baza nie przechowuje historii
lokalizacji odbiorców; współrzędne są przesyłane podczas pobierania okolicy.
API nie loguje URL-i żądań, aby nie zapisywać ich lokalizacji. Operator hostingu
może posiadać własne logi sieciowe. Mapy kontaktują się z OpenStreetMap, geofencing
z usługami Google Play. Zgłoszenia i hashe kluczy instalacji są przechowywane
do wygaśnięcia wpisu. Limity zawierają krótkotrwałe hashe adresów IP.

Przed Google Play: uzupełnij politykę prywatności o rzeczywistego operatora,
hosting i kontakt; sprawdź aktualne wymagania dotyczące lokalizacji w tle oraz
publicznych treści użytkowników. Ta wersja nie została opublikowana w sklepie.

## Test scenariusza na dwóch telefonach

1. Zainstaluj APK na obu telefonach i ustaw ten sam serwer.
2. Na A opublikuj wpis w miejscu z dobrym GPS. Na B odśwież okolicę.
3. Na B włącz komplet uprawnień oraz alerty. Wyjdź ponad 200–300 m od wpisu,
   wróć w jego okolicę z zablokowanym ekranem i odczekaj na reakcję systemu.
4. Otwórz powiadomienie. Ponowne wejście nie powinno wywołać drugiego alertu.
5. Usuń wpis na A. Po odświeżeniu B nie powinien go widzieć; API sprawdzane
   przed alertem nie powinno go zwrócić.
6. Sprawdź odmowę uprawnień, brak sieci, wyłączony GPS i restart telefonu.

Testy API ćwiczą komunikację HTTP i SQLite, w tym odczyt przez drugą instalację,
uprawnienia autora, wygaśnięcie, zgłaszanie, moderację oraz atomowe limity.
Testy JVM ćwiczą zasady wyboru wpisów do powiadomień oraz komunikację rzeczywistego
klienta Kotlin z API Python, w tym UTF-8, błędy, zgłaszanie i cofnięte wpisy.
Sprawdzają też, że klient nie przekazuje klucza instalacji przez przekierowania.
Same testy JVM nie
potwierdzają działania geofencingu ani GPS na fizycznym urządzeniu.
