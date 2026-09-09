package com.example.kassa

import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** Laravel is the only source of truth. Android discovers and executes hardware only. */
class LaravelHardwareBridge(
    private val webView: WebView,
    private val registry: HardwareRegistry,
    private val configService: HardwareConfigService,
    private val readerService: ReaderService,
    private val internalNfc: AndroidNfcService,
    private val sunmiNfc: SunmiNfcController,
    private val displayManager: CustomerDisplayManager,
    private val printerManager: SunmiPrinterManager,
    private val paymentManager: SumUpPaymentManager,
    private val updateManager: ApkUpdateManager,
    private val onConfigSnapshotChanged: (HardwareConfig) -> Unit
) {
    @Volatile private var nfcTestReaderId: String? = null
    @Volatile private var nfcTestResult: JSONObject? = null
    @Volatile private var activeNfcReaderId: String? = null
    @Volatile private var suspendedNfcReaderId: String? = null
    @Volatile private var requestedNfcReaderId: String? = null
    @Volatile private var nfcSwitchGeneration: Long = 0

    @JavascriptInterface fun getBridgeVersion() = "13"

    @JavascriptInterface fun getHardware(): String = JSONObject().put("bridgeVersion", 13)
        .put("hardware", registry.discover().toJsonArray()).put("laravelConfig", configService.load().toJson()).toString()
    @JavascriptInterface fun getHardwareConfig(): String = configService.load().toJson().toString()
    @JavascriptInterface fun setHardwareConfig(json: String): String = runCatching {
        val cfg = HardwareConfig.fromJson(JSONObject(json))
        configService.save(cfg)
        onConfigSnapshotChanged(cfg)

        RuntimeLog.add("hardware_config", "nfc=${cfg.nfcReaderId}; printer=${cfg.printerId}; display=${cfg.customerDisplayId}; payment=${cfg.paymentProviderId}; paymentTerminal=${cfg.paymentTerminalId}")
        ok().put("config", cfg.toJson()).toString()
    }.getOrElse { error("INVALID_CONFIG", it.message) }
    @JavascriptInterface fun rescanHardware(): String = registry.discover().toJsonArray().toString()
    @JavascriptInterface fun getLogs(): String = RuntimeLog.json()
    @JavascriptInterface fun clearLogs(): String { RuntimeLog.clear(); return ok().toString() }
    @JavascriptInterface fun getStatus(): String = JSONObject().put("success", true).put("bridgeVersion", 13)
        .put("hardware", registry.discover().toJsonArray()).put("config", configService.load().toJson())
        .put("payment", paymentManager.status()).put("nfcTest", getNfcTestStatusObject()).toString()

    @JavascriptInterface
    fun readNfc(readerId: String): String = runCatching {
        RuntimeLog.addHardware("nfc_select_requested", readerId)
        val found = registry.discover().any {
            it.type == HardwareType.NFC_READER && it.id == readerId && it.available
        }
        if (!found) return error("NFC_READER_UNAVAILABLE", readerId)

        // Laravel can call sync repeatedly. The same requested reader must be
        // idempotent and must never restart an in-flight SUNMI switch.
        if (requestedNfcReaderId == readerId) {
            return ok()
                .put("readerId", readerId)
                .put("active", activeNfcReaderId == readerId)
                .put("switching", activeNfcReaderId != readerId)
                .toString()
        }

        requestedNfcReaderId = readerId
        val generation = ++nfcSwitchGeneration

        when (readerId) {
            "android:nfc:front", "android:nfc:customer" -> {
                readerService.stop()

                fun finishNfcSwitch(switched: Boolean) {
                    webView.post {
                        if (generation != nfcSwitchGeneration || requestedNfcReaderId != readerId) return@post

                        if (!switched) {
                            RuntimeLog.addHardware("nfc_selected", readerId, "FAILED", "SUNMI_NFC_SWITCH_FAILED")
                            requestedNfcReaderId = activeNfcReaderId
                            return@post
                        }

                        internalNfc.stop()
                        webView.postDelayed({
                            if (generation != nfcSwitchGeneration || requestedNfcReaderId != readerId) return@postDelayed

                            if (internalNfc.start()) {
                                activeNfcReaderId = readerId
                                RuntimeLog.addHardware("nfc_selected", readerId, "OK")

                                // Self-service: only after Host NFC is confirmed
                                // do we physically power off the customer display.
                                if (readerId == "android:nfc:front") {
                                    displayManager.powerOff()
                                }
                            } else {
                                RuntimeLog.addHardware("nfc_selected", readerId, "FAILED", "readerMode start failed")
                            }
                        }, SUNMI_NFC_SETTLE_MS)
                    }
                }

                if (readerId == "android:nfc:customer") {
                    // Served: customer display/subsystem must be powered first.
                    // Do not change the working NFC selection logic; call the
                    // same selectCustomer() only after Android sees the display.
                    if (!displayManager.powerOn()) {
                        requestedNfcReaderId = activeNfcReaderId
                        return error("DISPLAY_POWER_ON_FAILED", readerId)
                    }

                    displayManager.waitUntilReady(SUNMI_DISPLAY_READY_TIMEOUT_MS) { ready ->
                        if (generation != nfcSwitchGeneration || requestedNfcReaderId != readerId) return@waitUntilReady

                        if (!ready) {
                            RuntimeLog.addHardware("nfc_selected", readerId, "FAILED", "CUSTOMER_DISPLAY_NOT_READY")
                            requestedNfcReaderId = activeNfcReaderId
                            return@waitUntilReady
                        }

                        sunmiNfc.selectCustomer(::finishNfcSwitch)
                    }
                } else {
                    // Existing, proven Host-NFC switch remains unchanged.
                    sunmiNfc.selectHost(::finishNfcSwitch)
                }
            }

            "android:nfc:internal" -> {
                readerService.stop()
                internalNfc.stop()
                if (!internalNfc.start()) {
                    requestedNfcReaderId = activeNfcReaderId
                    return error("NFC_READER_UNAVAILABLE", readerId)
                }
                activeNfcReaderId = readerId
            }

            else -> {
                // Lenovo/ACS/CCID path: completely independent from SUNMI.
                internalNfc.stop()
                readerService.setPreferredReaderId(readerId)
                readerService.start()
                activeNfcReaderId = readerId
            }
        }

        ok().put("readerId", readerId).put("switching", activeNfcReaderId != readerId).toString()
    }.getOrElse { error("NFC_START_FAILED", it.message) }

    @JavascriptInterface fun stopNfc(readerId: String): String {
        ++nfcSwitchGeneration
        if (requestedNfcReaderId == readerId) requestedNfcReaderId = null
        if (readerId.startsWith("android:nfc:")) internalNfc.stop() else readerService.stop()
        if (activeNfcReaderId == readerId) activeNfcReaderId = null
        return ok().put("readerId", readerId).toString()
    }

    /**
     * Recovery only: re-arms Android ReaderMode for the already selected
     * SUNMI antenna. The proven Host/Customer switch and display sequencing
     * are deliberately left untouched.
     */
    @JavascriptInterface
    fun recoverNfc(readerId: String): String = runCatching {
        if (
            readerId != "android:nfc:front"
            && readerId != "android:nfc:customer"
            && readerId != "android:nfc:internal"
        ) {
            return error("NFC_RECOVERY_UNSUPPORTED", readerId)
        }

        if (requestedNfcReaderId != readerId || activeNfcReaderId != readerId) {
            return error("NFC_RECOVERY_NOT_ACTIVE", readerId)
        }

        RuntimeLog.add("nfc_recovery_requested", readerId)

        val restarted = internalNfc.restartReaderMode()

        RuntimeLog.add(
            "nfc_recovery_result",
            "$readerId/${if (restarted) "OK" else "FAILED"}"
        )

        if (!restarted) {
            return error("NFC_RECOVERY_FAILED", readerId)
        }

        ok()
            .put("readerId", readerId)
            .put("active", true)
            .toString()
    }.getOrElse { error("NFC_RECOVERY_FAILED", it.message) }

    @JavascriptInterface fun testNfc(readerId: String): String {
        if (nfcTestReaderId != null) return error("NFC_TEST_BUSY", nfcTestReaderId)
        nfcTestReaderId = readerId
        nfcTestResult = JSONObject().put("success", true).put("running", true).put("readerId", readerId)
        val start = readNfc(readerId)
        if (!JSONObject(start).optBoolean("success")) { nfcTestReaderId = null; nfcTestResult = JSONObject(start).put("running", false).put("done", true); return nfcTestResult.toString() }
        webView.postDelayed({
            if (nfcTestReaderId == readerId) {
                stopNfc(readerId); nfcTestReaderId = null
                nfcTestResult = JSONObject().put("success", false).put("running", false).put("done", true)
                    .put("code", "NFC_TEST_TIMEOUT").put("readerId", readerId)
                emit("onNfcTestResult", nfcTestResult.toString())
            }
        }, 15_000)
        return nfcTestResult.toString()
    }
    @JavascriptInterface fun getNfcTestStatus(): String = getNfcTestStatusObject().toString()
    private fun getNfcTestStatusObject(): JSONObject = nfcTestResult ?: JSONObject().put("success", true).put("running", false).put("done", false)

    @JavascriptInterface fun showCustomerDisplay(deviceId: String, html: String): String {
        val success = displayManager.show(deviceId, html)
        RuntimeLog.addHardware(
            "display_bridge",
            deviceId,
            if (success) "OK" else "FAILED",
            "htmlLength=${html.length}"
        )
        return if (success) {
            ok().toString()
        } else {
            error("DISPLAY_UNAVAILABLE", deviceId)
        }
    }
    @JavascriptInterface fun clearCustomerDisplay(deviceId: String): String {
        // Physical OFF is coordinated after Host NFC becomes active.
        return ok().toString()
    }
    @JavascriptInterface fun print(printerId: String, json: String): String = runCatching {
        RuntimeLog.addHardware("printer_bridge_requested", printerId)
        val result = printerManager.print(printerId, JSONObject(json))
        RuntimeLog.addHardware("printer_bridge", printerId, if (result.optBoolean("success") || result.optBoolean("submitted")) "OK" else "FAILED", "code=${result.optString("code")}; state=${result.optString("stateText")}")
        result.toString()
    }.getOrElse { RuntimeLog.addHardware("printer_bridge", printerId, "FAILED", it.message ?: it.javaClass.simpleName); error("PRINT_INVALID", it.message) }
    @JavascriptInterface fun getPrinterStatus(): String = printerManager.status().put("success", true).toString()
    @JavascriptInterface fun testDisplay(displayId: String): String = showCustomerDisplay(displayId, "<html><body style='font-family:sans-serif;text-align:center;padding-top:20%'><h1>Display OK</h1><p>Android Bridge v12</p></body></html>")
    @JavascriptInterface fun testPrinter(printerId: String): String = printerManager.print(printerId, JSONObject().put("text", "YCH Clubheim\nAndroid Hardwaretest\nBridge v12\n").put("feedLines", 3).put("cut", true)).toString()

    // SumUp Android integration is Tap-to-Pay on INTERNAL NFC only.
    // External SumUp readers are controlled by Laravel via the SumUp Cloud API.
    @JavascriptInterface fun initializeSumUpTapToPay(accessToken: String): String =
        runCatching { paymentManager.initialize(accessToken).toString() }.getOrElse { error("SUMUP_TTP_INIT_FAILED", it.message) }
    @JavascriptInterface fun acknowledgeInterruptedPayment(requestId: String): String =
        if (paymentManager.acknowledgeInterrupted(requestId.takeIf { it.isNotBlank() })) ok().toString() else error("PAYMENT_RECOVERY_ID_MISMATCH", requestId)
    @JavascriptInterface fun resetPaymentState(): String = runCatching { paymentManager.resetState(); ok().toString() }.getOrElse { error("PAYMENT_RESET_FAILED", it.message) }
    @JavascriptInterface fun getPaymentStatus(): String = runCatching { paymentManager.status().put("success", true).toString() }.getOrElse { error("PAYMENT_STATUS_FAILED", it.message) }
    @JavascriptInterface fun startPayment(json: String): String = runCatching {
        // Tap-to-Pay owns the internal NFC controller while a card payment is active.
        // Pause whichever membership reader Laravel had activated and restore it after the payment result.
        suspendMembershipNfcForPayment()
        val response = paymentManager.start(JSONObject(json))
        if (!response.optBoolean("success", false)) restoreMembershipNfcAfterPayment()
        response.toString()
    }.getOrElse {
        restoreMembershipNfcAfterPayment()
        error("PAYMENT_INVALID", it.message)
    }

    @JavascriptInterface fun getAppInfo(): String {
        val context = webView.context
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return JSONObject().put("success", true).put("versionCode", versionCode).put("versionName", info.versionName)
            .put("bridgeVersion", 13).put("androidVersion", android.os.Build.VERSION.RELEASE)
            .put("manufacturer", android.os.Build.MANUFACTURER).put("model", android.os.Build.MODEL).toString()
    }
    @JavascriptInterface fun installUpdate(json: String): String = runCatching { updateManager.install(JSONObject(json)).toString() }.getOrElse { error("UPDATE_INVALID", it.message) }
    @JavascriptInterface fun getUpdateStatus(): String = updateManager.status().toString()

    private fun suspendMembershipNfcForPayment() {
        if (suspendedNfcReaderId != null) return
        val reader = activeNfcReaderId ?: return
        suspendedNfcReaderId = reader
        stopNfc(reader)
        RuntimeLog.add("nfc_suspended_for_payment", reader)
    }

    private fun restoreMembershipNfcAfterPayment() {
        val reader = suspendedNfcReaderId ?: return
        suspendedNfcReaderId = null
        val result = readNfc(reader)
        RuntimeLog.add("nfc_restored_after_payment", "$reader/$result")
    }

    fun notifyUpdateStatus(json: JSONObject) { emit("onUpdateStatus", json.toString()) }
    fun notifyHardwareChanged() { RuntimeLog.add("hardware_changed"); emit("onHardwareChanged", getHardware()) }
    fun notifyPaymentResult(json: String) { restoreMembershipNfcAfterPayment(); RuntimeLog.add("payment_result", json); emit("onPaymentResult", json) }
    fun notifyNfc(uid: String, readerId: String, readerName: String) {
        if (nfcTestReaderId == readerId) {
            stopNfc(readerId); nfcTestReaderId = null
            nfcTestResult = JSONObject().put("success", true).put("running", false).put("done", true)
                .put("readerId", readerId).put("readerName", readerName).put("uid", uid)
            RuntimeLog.add("nfc_test_ok", "$readerId/$uid")
            emit("onNfcTestResult", nfcTestResult.toString())
            return
        }
        emit("onNfc", JSONObject().put("uid", uid).put("readerId", readerId).put("readerName", readerName).toString())
    }
    fun notifyNfcRemoved(readerId: String, readerName: String) {
        if (nfcTestReaderId == readerId) return
        emit("onNfcRemoved", JSONObject().put("readerId", readerId).put("readerName", readerName).toString())
    }

    private fun emit(name: String, json: String) = webView.post { webView.evaluateJavascript("window.AndroidBridge && window.AndroidBridge.$name && window.AndroidBridge.$name($json);", null) }

private fun ok() = JSONObject().put("success", true)
    private fun error(code: String, msg: String?) = JSONObject().put("success", false).put("code", code).put("message", msg ?: "").toString()
    companion object {
        private const val SUNMI_NFC_SETTLE_MS = 700L
        private const val SUNMI_DISPLAY_READY_TIMEOUT_MS = 4_000L
    }
}
