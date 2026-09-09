# Android Bridge v9 - Go-Live mit externem SumUp Reader

Dieser Build enthaelt **nicht** das private SumUp Tap-to-Pay SDK.

## Zahlung
- Externer SumUp Reader/Solo: Laravel -> SumUp API. Android ist nicht beteiligt.
- Internes NFC / Tap-to-Pay: Bridge-Vertrag bleibt vorhanden, meldet aber `SUMUP_TTP_NOT_AVAILABLE`.
- `getHardware()` meldet `sumup:tap-to-pay` mit `available=false` und `reason=SDK_NOT_INCLUDED`.

Dadurch ist fuer diesen Build weder das private SumUp Maven Repository noch ein Maven Benutzer/Passwort erforderlich.
Sobald SumUp den SDK-Zugang freischaltet, kann eine spaetere APK die gleiche Bridge wieder mit Tap-to-Pay implementieren.

Alle anderen Funktionen bleiben erhalten: ACS/USB-NFC, internes NFC fuer Mitglieder, SUNMI Drucker, Kundendisplay, Diagnose, Kiosk und APK-Update.
