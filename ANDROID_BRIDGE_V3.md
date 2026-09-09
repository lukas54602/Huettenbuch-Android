# Android Hardware Bridge v3

Laravel is the only source of truth. Android discovers hardware, executes explicit commands and shows a read-only snapshot of Laravel's configuration.

## Discovery / status
- `Android.getBridgeVersion()` -> `"3"`
- `Android.getHardware()` -> discovered devices + last Laravel config snapshot
- `Android.rescanHardware()` -> discovered devices
- `Android.getHardwareConfig()` -> read-only snapshot last supplied by Laravel
- `Android.setHardwareConfig(json)` -> called by Laravel to update the read-only local snapshot shown in diagnostics

## NFC
- `Android.readNfc(readerId)` -> starts exactly the reader ID selected by Laravel
- `Android.stopNfc(readerId)`
- USB CCID/ACS IDs: `usb:ccid:<serial-or-device-key>`
- Internal NFC ID: `android:nfc:internal`

## Customer display
- `Android.showCustomerDisplay(displayId, html)`
- `Android.clearCustomerDisplay(displayId)`
- Secondary Android displays are exposed as `display:<id>`.
- Laravel supplies all displayed content.

## SUNMI printer
- `Android.print("sunmi:printer:internal", json)`
- Payload: `{ "text":"...", "fontSize":24, "feedLines":3, "cut":true }`
- Laravel supplies receipt content and decides when to print.

## SumUp
- `Android.sumUpLogin(affiliateKey, accessToken)`
- `Android.openSumUpReaderSettings()`
- `Android.getSumUpReader()`
- `Android.startPayment(json)`
- Payment JSON can contain `readerId`; if supplied it must match the paired SumUp reader ID.
- Required fields: `requestId`, `amount`; optional: `currency`, `title`, `readerId`.
- Android prevents a second concurrent checkout; Laravel owns payment/business state.

## Callbacks
- `window.AndroidBridge.onHardwareChanged(payload)`
- `window.AndroidBridge.onPaymentResult(payload)`

## Configuration rule
There are no Android hardware dropdowns and Android does not choose NFC/payment/printer/display devices. The Android admin screen only shows what Laravel configured and whether those devices are currently detected.
