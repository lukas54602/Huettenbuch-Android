# Android Hardware Bridge v4 - Go-Live Candidate

Laravel is the only source of truth. Android discovers hardware, executes explicit commands and reports results/status. The local hardware snapshot is read-only and only exists for diagnostics/offline visibility.

## Discovery/config snapshot
- `Android.getBridgeVersion()` -> `4`
- `Android.getHardware()`
- `Android.rescanHardware()`
- `Android.setHardwareConfig(json)` stores the Laravel-supplied read-only snapshot.
- `Android.getHardwareConfig()`
- `Android.getStatus()`
- `Android.getLogs()` / `Android.clearLogs()`

## NFC
- `Android.readNfc(readerId)`
- `Android.stopNfc(readerId)`
- callback `window.AndroidBridge.onNfc({uid,readerId,readerName})`
- callback `window.AndroidBridge.onNfcRemoved({readerId,readerName})` for USB CCID

## Customer display
- `Android.showCustomerDisplay(displayId, html)`
- `Android.clearCustomerDisplay(displayId)`

## Printer
- `Android.print(printerId, json)`; receipt content/layout comes from Laravel.

## SumUp SDK
- `Android.sumUpLogin(affiliateKey, accessToken)`
- `Android.openSumUpReaderSettings()`
- `Android.getSumUpReader()`
- `Android.startPayment(json)`
- `Android.getPaymentStatus()`
- `Android.resetPaymentState()` only after Laravel has reconciled an interrupted payment.
- callback `window.AndroidBridge.onPaymentResult(...)`

`startPayment` accepts `requestId`, `amount`, `currency`, `title`, and optional `readerId`. If a reader ID is supplied it must match the reader currently paired in the SumUp SDK. Reader pairing itself is performed by the SumUp SDK reader setup UI; Android does not silently choose another reader.

## Hardware changes
USB attach/detach and secondary-display add/remove/change trigger `window.AndroidBridge.onHardwareChanged(...)`.

## Stable USB IDs
A USB serial number is used when available. If the reader exposes no serial number, the deterministic fallback is VID:PID. Therefore two simultaneously attached identical no-serial CCID readers cannot be uniquely persisted and should not be used as separate configured readers.

## Go-live physical tests required
Build in Android Studio, then test SUNMI T3 and Lenovo: cold boot, kiosk/autostart, ACS permission/read/remove/reconnect, internal NFC, secondary display, printer/no-paper/recovery, SumUp login/pair/payment/cancel/decline/interrupted payment, network loss/recovery, and WebView renderer recovery.
