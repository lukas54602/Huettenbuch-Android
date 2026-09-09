package com.example.kassa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * SUNMI T3 NFC selector.
 *
 * Uses the public control entry exposed by the privileged SUNMI UsbScreen
 * system app. SUNMI itself maps NFC_SW values as follows:
 *   -1 = query state, 0 = NFC off, 1 = Host NFC, 2 = Customer-display NFC.
 *
 * The privileged SUNMI process performs the complete internal switch,
 * including external NFC configuration and reset. This app does not access
 * SunmiCustomerManager, Settings.Global or USB interfaces directly.
 */
class SunmiNfcController(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var requested: Target? = null
    @Volatile private var generation = 0L
    @Volatile private var pendingCallback: ((Boolean) -> Unit)? = null
    @Volatile private var receiverRegistered = false

    enum class Target { HOST, CUSTOMER }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_SET_RESULT) return
            if (intent.getIntExtra(EXTRA_TYPE, -1) != TYPE_OPTION) return
            if (intent.getIntExtra(EXTRA_KEY, -1) != KEY_NFC_SW) return

            val value = intent.getIntExtra(EXTRA_VALUE, -1)
            val target = requested ?: return
            val expectedState = when (target) {
                Target.HOST -> STATE_HOST
                Target.CUSTOMER -> STATE_CUSTOMER
            }

            // SUNMI returns 0 for a successful SET operation and -1 on error.
            // A query (-1) returns the current state 0/1/2, but we don't use
            // query mode for switching here.
            val ok = value == RESULT_OK

            RuntimeLog.addHardware(
                "sunmi_nfc_switch",
                target.name.lowercase(),
                if (ok) "OK" else "FAILED",
                "result=$value;expected=$expectedState"
            )

            finishPending(ok)
        }
    }

    init {
        registerResultReceiver()
    }

    fun isSupported(): Boolean =
        isSunmi() && runCatching {
            appContext.packageManager.getPackageInfo(SUNMI_USB_SCREEN_PACKAGE, 0)
            true
        }.getOrDefault(false)

    fun currentTarget(): Target? = requested

    fun selectHost(callback: (Boolean) -> Unit) = select(Target.HOST, callback)

    fun selectCustomer(callback: (Boolean) -> Unit) = select(Target.CUSTOMER, callback)

    @Synchronized
    private fun select(target: Target, callback: (Boolean) -> Unit) {
        if (!isSupported()) {
            main.post { callback(false) }
            return
        }

        // Replace any stale pending request. Laravel may resync frequently.
        pendingCallback?.let { old -> main.post { old(false) } }

        requested = target
        pendingCallback = callback
        val myGeneration = ++generation

        val state = when (target) {
            Target.HOST -> STATE_HOST
            Target.CUSTOMER -> STATE_CUSTOMER
        }

        val serial = if (target == Target.CUSTOMER) customerDisplaySerial().orEmpty() else ""

        val sent = runCatching {
            val intent = Intent(ACTION_SET_CONTROL).apply {
                setPackage(SUNMI_USB_SCREEN_PACKAGE)
                putExtra(EXTRA_SN, serial)
                putExtra(EXTRA_TYPE, TYPE_OPTION)
                putExtra(EXTRA_KEY, KEY_NFC_SW)
                putExtra(EXTRA_VALUE, state)
            }
            appContext.sendBroadcast(intent)
            true
        }.onFailure {
            RuntimeLog.addHardware(
                "sunmi_nfc_switch",
                target.name.lowercase(),
                "FAILED",
                it.message ?: it.javaClass.simpleName
            )
        }.getOrDefault(false)

        if (!sent) {
            finishPending(false)
            return
        }

        RuntimeLog.addHardware(
            "sunmi_nfc_switch",
            target.name.lowercase(),
            "REQUESTED",
            "value=$state;sn=${serial.ifEmpty { "host" }}"
        )

        // SUNMI answers asynchronously through ACTION_SET_RESULT.
        main.postDelayed({
            synchronized(this) {
                if (generation == myGeneration && pendingCallback != null) {
                    RuntimeLog.addHardware(
                        "sunmi_nfc_switch",
                        target.name.lowercase(),
                        "FAILED",
                        "RESULT_TIMEOUT"
                    )
                    finishPending(false)
                }
            }
        }, SWITCH_TIMEOUT_MS)
    }

    @Synchronized
    private fun finishPending(ok: Boolean) {
        val callback = pendingCallback ?: return
        pendingCallback = null
        main.post { callback(ok) }
    }

    fun customerDisplaySerial(): String? {
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = (
            dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).asList() +
                dm.displays.asList()
            )
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
            .distinctBy { it.displayId }

        displays.forEach { display ->
            SERIAL_REGEX.find(display.name)?.value?.let { return it }
        }
        return null
    }

    private fun registerResultReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_SET_RESULT)
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(resultReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(resultReceiver, filter)
        }
        receiverRegistered = true
    }

    fun dispose() {
        synchronized(this) {
            generation++
            requested = null
            pendingCallback = null
        }
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(resultReceiver) }
            receiverRegistered = false
        }
    }

    private fun isSunmi(): Boolean =
        Build.MANUFACTURER.contains("SUNMI", true) ||
            Build.BRAND.contains("SUNMI", true)

    companion object {
        private const val SUNMI_USB_SCREEN_PACKAGE = "com.sunmi.usbscreen"

        private const val ACTION_SET_CONTROL = "com.sunmi.usbscreen.ACTION_SET_CONTROL"
        private const val ACTION_SET_RESULT = "com.sunmi.usbscreen.ACTION_SET_RESULT"

        private const val EXTRA_SN = "sn"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_VALUE = "value"

        // ApiUtil: type=1 is OPTION, key=6 is NFC_SW.
        private const val TYPE_OPTION = 1
        private const val KEY_NFC_SW = 6

        // Decompiled ApiUtil$handleSetControl$1:
        // 0 = NFC off, 1 = Host NFC, 2 = Customer-display NFC.
        private const val STATE_HOST = 1
        private const val STATE_CUSTOMER = 2

        // ApiUtil responds with value=0 after a successful SET operation.
        private const val RESULT_OK = 0

        private const val SWITCH_TIMEOUT_MS = 6_000L
        private val SERIAL_REGEX = Regex("[A-Z]{1,4}[0-9]{8,}")
    }
}
