# Android Hardware Bridge v7 – final Go-Live interface

Laravel is the only source of truth for productive hardware selection and all business logic. Android only discovers hardware, executes explicit commands, reports status/results and shows a read-only diagnostic snapshot of the configuration supplied by Laravel.

## Discovery / status
- `Android.getBridgeVersion()` -> `7`
- `Android.getHardware()`
- `Android.rescanHardware()`
- `Android.getStatus()`
- `Android.getLogs()` / `Android.clearLogs()`
- `Android.setHardwareConfig(json)` stores a read-only diagnostic snapshot supplied by Laravel; it never chooses hardware.

## NFC
- `Android.readNfc(readerId)`
- `Android.stopNfc(readerId)`
- `Android.testNfc(readerId)` starts an isolated diagnostic test; the UID is never sent to the productive `onNfc` callback.
- `Android.getNfcTestStatus()` polls the isolated test result.
- callbacks: `window.AndroidBridge.onNfc(...)`, `onNfcRemoved(...)`, `onNfcTestResult(...)`

## Display
- `Android.showCustomerDisplay(displayId, html)`
- `Android.clearCustomerDisplay(displayId)`
- `Android.testDisplay(displayId)`

## SUNMI printer
- `Android.print(printerId, json)` supports `text`, `imageBase64`, `qrCode`, `fontSize`, `feedLines`, `cut`.
- `Android.getPrinterStatus()` exposes SUNMI printer state where supported.
- `Android.testPrinter(printerId)`

## SumUp SDK
- `Android.sumUpLogin(affiliateKey, accessToken)`
- `Android.getSumUpReader()`
- `Android.prepareSumUpReader(readerId)` reconnects/prepares the reader already saved by SumUp.
- `Android.testSumUp(readerId)` + `Android.getSumUpTestStatus()` perform an asynchronous readiness test and only report success once the reader is actually connected.
- `Android.openSumUpReaderSettings()` opens SumUp's official reader setup UI when Laravel explicitly requests it.
- `Android.startPayment(json)`; Laravel supplies requestId, amount, currency, title and optional readerId. Android waits up to 15 seconds for the selected saved reader to become connected before checkout is opened.
- `Android.getPaymentStatus()` includes `recoveryRequired` / `interruptedRequestId` after Android process death.
- `Android.acknowledgeInterruptedPayment(requestId)` after Laravel reconciles the interrupted transaction.
- callback: `window.AndroidBridge.onPaymentResult(...)`.

Important: SumUp Android SDK does not expose silent pairing of an arbitrary reader serial. A requested reader must be the SDK's saved reader; otherwise the bridge returns `SUMUP_READER_SETUP_REQUIRED`. Laravel may then explicitly invoke the official reader setup page.


## Configuration boundary
Server URL, terminal name and admin PIN remain local Android service settings. Productive hardware selection is never editable in Android; it is supplied by Laravel and displayed read-only. Laravel may alternatively handle SumUp itself and not call the Android payment bridge.


## Build compatibility

- SumUp Android SDK: 7.1.0
- compileSdk / targetSdk: 36
- Java: 17
- Compose BOM: 2023.06.01 (required by SumUp transitive Compose dependencies)
- desugar_jdk_libs: 2.1.5
