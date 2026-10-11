# Weryfikacja GPSChat

- 8 testów API Python i SQLite: zaliczone.
- 4 testy zasad powiadomień oraz 5 testów integracji klienta Kotlin z rzeczywistym API: zaliczone.
- assembleDebug, testDebugUnitTest i lintDebug: zaliczone; Android Lint bez uwag.
- Podpis debug APK zweryfikowany przez apksigner.
- Kontener API: publikacja i odczyt z drugiej instalacji zaliczone.
- Konfiguracja Docker Compose i Caddy: poprawna.
- APK zainstalowany w emulatorze Android 11. Interfejs System UI emulatora zawiesza się przy emulacji bez KVM; test interfejsu aplikacji pozostaje niepotwierdzony.

Nie zweryfikowano na fizycznym telefonie GPS, wejścia w geofence ani powiadomień w tle. Procedura testu na dwóch telefonach znajduje się w README.md. Nie uruchomiono publicznego hostingu. APK używa podpisu debug i służy do testów; publikacja wymaga podpisu wydania.
