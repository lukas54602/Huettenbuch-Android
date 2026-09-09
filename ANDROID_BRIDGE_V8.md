# Android Bridge v8

Laravel is the source of truth. Android only discovers and executes hardware.

## SumUp split
- External SumUp reader / Solo: Laravel -> SumUp Cloud API. Android is not involved.
- Internal NFC card payment: Laravel -> Android Bridge -> SumUp Android Tap-to-Pay SDK 1.1.6 -> internal NFC.

## Bridge calls for internal NFC payment
- `Android.initializeSumUpTapToPay(accessToken)`
- `Android.startPayment(json)`
- `Android.getPaymentStatus()`
- `Android.acknowledgeInterruptedPayment(requestId)`
- `Android.resetPaymentState()`

Example:
```json
{"requestId":"sale-123","amount":"12.50","skipSuccessScreen":false,"timeoutCardWaitSeconds":120}
```

The access token is supplied by Laravel and is not persisted by Android.

## SumUp Tap-to-Pay Maven credentials
The Tap-to-Pay Maven repository is restricted by SumUp. Put the credentials supplied by SumUp in the user-level `~/.gradle/gradle.properties`, never in Git or the project ZIP:

```
SUMUP_TTP_MAVEN_USERNAME=...
SUMUP_TTP_MAVEN_PASSWORD=...
```
