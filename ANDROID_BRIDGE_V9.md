# Android Bridge v9

Laravel remains the only source of truth for POS and hardware configuration. Android only discovers hardware, executes commands and exposes diagnostics.

## Payment
- External SumUp reader / Solo: handled directly by Laravel through the SumUp API; Android is not involved.
- SUNMI/internal NFC card payment: Laravel supplies a short-lived token and calls the SumUp Tap-to-Pay bridge.
- While Tap-to-Pay is active, Android temporarily stops the membership NFC reader selected by Laravel and restores it after payment success, cancellation or error.

## Updates
Laravel can query `Android.getAppInfo()` and compare `versionCode`, `versionName` and `bridgeVersion` with the release it publishes.

To install an update Laravel calls:

```javascript
Android.installUpdate(JSON.stringify({
  versionCode: 11,
  apkUrl: "https://example.invalid/kassa-11.apk",
  sha256: "<64 hex chars>"
}))
```

Android downloads only over HTTPS, verifies SHA-256, package name and that the APK uses the same signing certificate as the installed app, then submits it to Android PackageInstaller. If the device permits unattended installation it proceeds silently; otherwise Android shows the system confirmation screen. Progress is available through `Android.getUpdateStatus()` and `window.AndroidBridge.onUpdateStatus(...)`.

## Version / diagnostics
- `Android.getBridgeVersion()` -> `9`
- `Android.getAppInfo()` -> app version, bridge version, Android version, manufacturer and model
- `Android.getHardware()` / `Android.getStatus()`
- `Android.getLogs()`
