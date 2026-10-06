package com.example.kassa

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.webkit.WebView
import android.webkit.WebViewClient

class CustomerDisplayManager(
    context: Context
) : DisplayManager.DisplayListener {

    private val activityContext = context
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var presentation: Presentation? = null
    private var presentationWebView: WebView? = null
    private var activeAndroidDisplayId: Int? = null
    private var lastHtml: String? = null
    private var blankRequested = true
    private var disposed = false

    init {
        displayManager.registerDisplayListener(this, mainHandler)
    }

    /**
     * Physically powers the SUNMI customer display on/off through the same
     * external control API used by SUNMI UsbScreen.
     *
     * This does not change NFC selection. The bridge coordinates ordering.
     */
    fun powerOn(): Boolean = setPhysicalPower(true)

    fun powerOff(): Boolean = setPhysicalPower(false)

    fun isCustomerDisplayReady(): Boolean = resolveDisplay(SUNMI_CUSTOMER_DISPLAY_ID) != null

    fun waitUntilReady(timeoutMs: Long, callback: (Boolean) -> Unit) {
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs

        fun poll() {
            if (disposed) {
                callback(false)
                return
            }

            if (isCustomerDisplayReady()) {
                RuntimeLog.add("display_power_ready", "ready=true")
                callback(true)
                return
            }

            if (android.os.SystemClock.uptimeMillis() >= deadline) {
                RuntimeLog.add("display_power_ready", "ready=false;timeoutMs=$timeoutMs")
                callback(false)
                return
            }

            mainHandler.postDelayed({ poll() }, DISPLAY_READY_POLL_MS)
        }

        mainHandler.post { poll() }
    }

    private fun setPhysicalPower(enabled: Boolean): Boolean {
        if (disposed) return false

        // Dismiss before physical power-off so Presentation does not hold a
        // disappearing Android Display. Do not blank: the panel is really off.
        if (!enabled) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                dismissInternal("physical_power_off")
            } else {
                mainHandler.post { dismissInternal("physical_power_off") }
            }
        }

        val serial = customerDisplaySerial()
        if (serial.isNullOrBlank()) {
            RuntimeLog.add(
                "display_power_failed",
                "enabled=$enabled;reason=CUSTOMER_DISPLAY_SN_NOT_FOUND"
            )
            return false
        }

        return runCatching {
            val intent = Intent(ACTION_SET_CONTROL).apply {
                setPackage(SUNMI_USB_SCREEN_PACKAGE)
                putExtra("sn", serial)
                putExtra("type", TYPE_OPTION)
                putExtra("key", KEY_SCREEN_SW)
                putExtra("value", if (enabled) VALUE_ON else VALUE_OFF)
            }
            activityContext.sendBroadcast(intent)
            RuntimeLog.add(
                "display_power_requested",
                "enabled=$enabled"
            )
            true
        }.getOrElse {
            RuntimeLog.add(
                "display_power_failed",
                "enabled=$enabled;${it.javaClass.simpleName}:${it.message}"
            )
            false
        }
    }

    private fun hideCustomerDisplayNumber(serial: String) {
        runCatching {
            val intent = Intent(ACTION_SET_CONTROL).apply {
                setPackage(SUNMI_USB_SCREEN_PACKAGE)
                putExtra("sn", serial)
                putExtra("type", TYPE_OPTION)
                putExtra("key", KEY_NICKNAME_SHOW)
                putExtra("value", VALUE_OFF)
            }
            activityContext.sendBroadcast(intent)
            RuntimeLog.add("display_number_hidden")
        }.onFailure {
            RuntimeLog.add(
                "display_number_hide_failed",
                "${it.javaClass.simpleName}:${it.message}"
            )
        }
    }

    private fun customerDisplaySerial(): String? {
        val displays = (
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).asList() +
                displayManager.displays.asList()
            )
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
            .distinctBy { it.displayId }

        displays.forEach { display ->
            SERIAL_REGEX.find(display.name)?.value?.let { return it }
        }

        // The display may already be powered off. SUNMI stores the nickname
        // under a key containing the serial, but Android Settings is not needed
        // here; callers should power off only after the display was detected.
        return lastCustomerDisplaySerial
    }

    fun show(deviceId: String, html: String): Boolean {
        if (disposed) return false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { show(deviceId, html) }
            return true
        }

        RuntimeLog.add(
            "display_show_requested",
            "deviceId=$deviceId;htmlLength=${html.length}"
        )

        lastHtml = html
        blankRequested = false
        return render(deviceId, html, false)
    }

    /**
     * Keeps ownership of the external display but renders black.
     * Dismissing the Presentation makes SUNMI fall back to screen mirroring.
     */
    fun blank(): Boolean {
        if (disposed) return false
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { blank() }
            return true
        }

        RuntimeLog.add("display_blank_requested")
        blankRequested = true
        // Preserve lastHtml so a later served-mode activation can restore it.
        return render(SUNMI_CUSTOMER_DISPLAY_ID, BLACK_HTML, true)
    }

    /**
     * Bridge clear() must blank instead of dismissing.
     */
    fun clear(deviceId: String? = null) {
        blank()
    }

    fun onHostResume() {
        if (disposed) return
        mainHandler.post {
            if (blankRequested) {
                blank()
            } else {
                lastHtml?.let { show(SUNMI_CUSTOMER_DISPLAY_ID, it) }
            }
        }
    }

    /**
     * Do not dismiss here: Android admin/pause must not expose SUNMI mirroring.
     */
    fun onHostPause() {
        // Keep the current Presentation and desired state. System dialogs or
        // activity pauses must not turn the customer display black or dismiss it.
    }

    fun dispose() {
        disposed = true
        displayManager.unregisterDisplayListener(this)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            dismissInternal("dispose")
        } else {
            mainHandler.post { dismissInternal("dispose") }
        }
    }

    override fun onDisplayAdded(displayId: Int) {
        val added = displayManager.getDisplay(displayId)
        SERIAL_REGEX.find(added?.name ?: "")?.value?.let {
            lastCustomerDisplaySerial = it
            hideCustomerDisplayNumber(it)
        }
        RuntimeLog.add("display_added", "displayId=$displayId;name=${added?.name ?: ""}")
        mainHandler.post {
            blank()
            if (!blankRequested) {
                lastHtml?.let { html ->
                    mainHandler.post { show(SUNMI_CUSTOMER_DISPLAY_ID, html) }
                }
            }
        }
    }

    override fun onDisplayRemoved(displayId: Int) {
        RuntimeLog.add("display_removed", "displayId=$displayId")
        if (activeAndroidDisplayId == displayId) {
            presentation = null
            presentationWebView = null
            activeAndroidDisplayId = null
        }
    }

    override fun onDisplayChanged(displayId: Int) {
        val d = displayManager.getDisplay(displayId)
        SERIAL_REGEX.find(d?.name ?: "")?.value?.let { lastCustomerDisplaySerial = it }
        RuntimeLog.add(
            "display_changed",
            "displayId=$displayId;name=${d?.name ?: ""};state=${d?.state ?: -1};valid=${d?.isValid ?: false}"
        )
    }

    private fun render(deviceId: String, html: String, isBlank: Boolean): Boolean {
        val display = resolveDisplay(deviceId)
        if (display == null) {
            RuntimeLog.add("display_render_failed", "deviceId=$deviceId;reason=DISPLAY_UNAVAILABLE")
            return false
        }

        RuntimeLog.add(
            "display_resolved",
            "deviceId=$deviceId;displayId=${display.displayId};name=${display.name};state=${display.state};flags=${display.flags};blank=$isBlank"
        )

        val currentPresentation = presentation
        val currentWebView = presentationWebView
        RuntimeLog.add(
            "display_state_before",
            "presentation=${currentPresentation != null};showing=${currentPresentation?.isShowing == true};activeDisplayId=$activeAndroidDisplayId;webView=${currentWebView != null};blank=$isBlank"
        )

        if (
            currentPresentation?.isShowing == true &&
            activeAndroidDisplayId == display.displayId &&
            currentWebView != null
        ) {
            RuntimeLog.add(
                "display_webview_load",
                "mode=reuse;displayId=${display.displayId};blank=$isBlank;htmlLength=${html.length}"
            )
            loadHtml(currentWebView, html)
            return true
        }

        // A Presentation without its WebView is not a valid reusable state.
        // Recreate it rather than returning a false-positive success.
        dismissInternal("recreate")

        return runCatching {
            val p = object : Presentation(activityContext, display) {
                override fun onCreate(state: android.os.Bundle?) {
                    super.onCreate(state)

                    val web = WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = false
                        settings.domStorageEnabled = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                RuntimeLog.add(
                                    "display_page_started",
                                    "displayId=${display.displayId};url=${url ?: ""};blank=$isBlank"
                                )
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                RuntimeLog.add(
                                    "display_page_finished",
                                    "displayId=${display.displayId};url=${url ?: ""};blank=$isBlank;width=${view?.width ?: -1};height=${view?.height ?: -1};shown=${view?.isShown ?: false};visibility=${view?.visibility ?: -1}"
                                )
                            }
                        }
                    }

                    presentationWebView = web
                    setContentView(web)

                    RuntimeLog.add(
                        "display_webview_created",
                        "displayId=${display.displayId};blank=$isBlank"
                    )

                    loadHtml(web, html)
                }
            }

            p.setOnDismissListener {
                RuntimeLog.add(
                    "display_presentation_dismissed",
                    "displayId=${display.displayId}"
                )
                presentationWebView?.apply {
                    stopLoading()
                    removeAllViews()
                    destroy()
                }
                presentationWebView = null
                presentation = null
                activeAndroidDisplayId = null
            }

            p.show()
            presentation = p
            activeAndroidDisplayId = display.displayId

            RuntimeLog.add(
                "display_active",
                "displayId=${display.displayId};name=${display.name};blank=$isBlank;showing=${p.isShowing}"
            )
            true
        }.getOrElse {
            RuntimeLog.add(
                "display_render_failed",
                "${it.javaClass.simpleName}:${it.message}"
            )
            false
        }
    }

    private fun loadHtml(webView: WebView, html: String) {
        val encoded = android.util.Base64.encodeToString(
            html.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )

        webView.loadData(
            encoded,
            "text/html",
            "base64"
        )
    }

    private fun resolveDisplay(deviceId: String): Display? {
        if (
            deviceId != SUNMI_CUSTOMER_DISPLAY_ID &&
            !deviceId.startsWith("display:")
        ) return null

        val presentations =
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                .filter {
                    it.displayId != Display.DEFAULT_DISPLAY && it.isValid
                }

        if (deviceId == SUNMI_CUSTOMER_DISPLAY_ID) {
            val found = presentations.firstOrNull()
                ?: displayManager.displays.firstOrNull {
                    it.displayId != Display.DEFAULT_DISPLAY && it.isValid
                }

            if (found != null) {
                SERIAL_REGEX.find(found.name)?.value?.let {
                    lastCustomerDisplaySerial = it
                }
            }
            return found
        }

        val requestedId =
            deviceId.substringAfter("display:", "").toIntOrNull()

        val exact = requestedId?.let { displayManager.getDisplay(it) }
        if (
            exact != null &&
            exact.displayId != Display.DEFAULT_DISPLAY &&
            exact.isValid
        ) return exact

        return presentations.firstOrNull()
    }

    private fun dismissInternal(reason: String) {
        val p = presentation
        presentation = null
        activeAndroidDisplayId = null

        runCatching {
            if (p?.isShowing == true) {
                p.dismiss()
            } else {
                presentationWebView?.apply {
                    stopLoading()
                    removeAllViews()
                    destroy()
                }
                presentationWebView = null
            }
        }.onFailure {
            RuntimeLog.add(
                "display_dismiss_failed",
                "reason=$reason;${it.javaClass.simpleName}:${it.message}"
            )
        }
    }

    companion object {
        const val SUNMI_CUSTOMER_DISPLAY_ID = "sunmi:display:customer"

        private const val SUNMI_USB_SCREEN_PACKAGE = "com.sunmi.usbscreen"
        private const val ACTION_SET_CONTROL = "com.sunmi.usbscreen.ACTION_SET_CONTROL"
        private const val TYPE_OPTION = 1
        private const val KEY_SCREEN_SW = 2
        private const val KEY_NICKNAME_SHOW = 20
        private const val VALUE_OFF = 0
        private const val VALUE_ON = 1
        private const val DISPLAY_READY_POLL_MS = 100L
        private val SERIAL_REGEX = Regex("[A-Z]{1,4}[0-9]{8,}")

        @Volatile
        private var lastCustomerDisplaySerial: String? = null

        private const val BLACK_HTML =
            "<html><body style=\"margin:0;background:#000;width:100vw;height:100vh;overflow:hidden\"></body></html>"
    }
}
