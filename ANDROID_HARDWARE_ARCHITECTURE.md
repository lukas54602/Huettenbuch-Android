# Android hardware architecture - 1.2.1

Laravel remains the source of truth for terminal hardware configuration.

## SUNMI T3
- `android:nfc:front`: Host NFC.
- `android:nfc:customer`: NFC below Customer display 1.
- `SunmiNfcController` uses SUNMI's hidden framework `android.app.sunmi.SunmiCustomerManager` via reflection, so the APK remains portable to normal Android devices.
- Host sequence mirrors SUNMI UsbScreen: NFC off -> controller 0 -> NFC on.
- Customer sequence: request customer NFC through SUNMI UsbScreen `ACTION_SET_CONTROL` (`type=1`, `key=6`, display SN), wait for external controller, controller 1 -> `resetNfc()`.
- No NP521 USB claiming and no WRITE_SECURE_SETTINGS.

## Customer display
- Android `Presentation` owns the second display continuously.
- Self-service: black Presentation.
- Served mode: Laravel HTML.
- The physical display is not power-cycled on mode changes or Activity pause; this prevents reconnect races and SUNMI mirroring fallback.

## Lenovo / generic Android
- USB CCID / ACS readers stay on `ReaderService` and are independent of SUNMI code.
- Generic internal NFC is exposed as `android:nfc:internal`.
- SUNMI-only features are detected at runtime and are not required.

## Printer
- SUNMI internal printer uses the existing SUNMI printer service.
- Laravel supplies `lines[]`; Android renders text/feed/cut and safely scales images.
- On Lenovo the SUNMI printer is simply absent.

## Removed legacy paths
- Np521NfcSelector / Np521ProbeService
- OperatingModeStore
- USB interface claim/release as NFC selector
- Settings.Global NFC selector / WRITE_SECURE_SETTINGS
- Android-local operating mode
