package com.example.kassa

import android.app.ActivityManager
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity(), ReaderService.Listener {
    private lateinit var settingsService: SettingsService
    private lateinit var identityService: TerminalIdentityService
    private lateinit var apiService: ApiService
    private lateinit var readerService: ReaderService
    private lateinit var hardwareRegistry: HardwareRegistry
    private lateinit var internalNfcService: AndroidNfcService
    private lateinit var sunmiNfcController: SunmiNfcController
    private lateinit var hardwareConfigService: HardwareConfigService
    private lateinit var customerDisplayManager: CustomerDisplayManager
    private lateinit var sunmiPrinterManager: SunmiPrinterManager
    private lateinit var paymentManager: SumUpPaymentManager
    private lateinit var hardwareBridge: LaravelHardwareBridge
    private lateinit var apkUpdateManager: ApkUpdateManager
    private lateinit var hardwareChangeMonitor: HardwareChangeMonitor

    private lateinit var webView: WebView
    private lateinit var statusOverlay: View
    private lateinit var statusProgress: ProgressBar
    private lateinit var statusTitle: TextView
    private lateinit var statusMessage: TextView
    private lateinit var statusPrimaryButton: Button
    private lateinit var diagnosticsPanel: ScrollView
    private lateinit var diagnosticsText: TextView
    private lateinit var statusServerValue: TextView
    private lateinit var statusTerminalValue: TextView
    private lateinit var statusReaderValue: TextView

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newScheduledThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var lastConfigFingerprint: String? = null
    private var registrationPollFuture: ScheduledFuture<*>? = null
    private var webWatchdogFuture: ScheduledFuture<*>? = null
    private var dailyRebootFuture: ScheduledFuture<*>? = null

    private val startupGraceUntil = android.os.SystemClock.elapsedRealtime() + 45_000L
    @Volatile private var webWatchdogFailures = 0
    @Volatile private var webRecoveryInProgress = false

    private val checkingServer = AtomicBoolean(false)
    private val openingWebSession = AtomicBoolean(false)
    private val sendingUid = AtomicBoolean(false)

    @Volatile
    private var terminalActive = false

    @Volatile
    private var maintenanceMode = false

    private var webLoaded = false
    private var adminMode = false
    private var lastMainFrameHttpError: Int? = null
    private var lastServerState = ApiService.ServerState.OFFLINE
    private var lastUid: String? = null
    private var lastTransmissionAt: Date? = null
    private var lastTransmissionSuccessful: Boolean? = null
    private var lastError: String? = null

    private var adminTapCount = 0
    private var adminTapStartedAt = 0L
    private var adminDialogOpen = false

    private var primaryAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(false)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        settingsService = SettingsService(this)
        bindViews()
        configureWebView()
        configureDiagnosticsButtons()

        // Android Keystore/RSA kann auf schwächeren Tablets teuer sein.
        // Daher vollständig außerhalb des UI-Threads initialisieren.
        showStatus("Kassa wird gestartet", "Geräteidentität wird vorbereitet …", showProgress = true)
        networkExecutor.execute {
            identityService = TerminalIdentityService(applicationContext)
            apiService = ApiService(identityService)
            readerService = ReaderService(applicationContext, this)
            hardwareRegistry = HardwareRegistry(applicationContext)
            internalNfcService = AndroidNfcService(
                this,
                { uid, reader -> onCardPresented(uid, reader) },
                { reader -> onCardRemoved(reader) }
            )
            sunmiNfcController = SunmiNfcController(applicationContext)
            hardwareConfigService = HardwareConfigService(applicationContext)
            customerDisplayManager = CustomerDisplayManager(this)
            sunmiPrinterManager = SunmiPrinterManager(applicationContext)
            paymentManager = SumUpPaymentManager(this) { json -> if (::hardwareBridge.isInitialized) hardwareBridge.notifyPaymentResult(json) }
            apkUpdateManager = ApkUpdateManager(this) { json -> if (::hardwareBridge.isInitialized) hardwareBridge.notifyUpdateStatus(json) }
            runOnUiThread {
                hardwareBridge = LaravelHardwareBridge(
                    webView,
                    hardwareRegistry,
                    hardwareConfigService,
                    readerService,
                    internalNfcService,
                    sunmiNfcController,
                    customerDisplayManager,
                    sunmiPrinterManager,
                    paymentManager,
                    apkUpdateManager
                ) { onLaravelHardwareConfigSnapshot(it) }
                webView.addJavascriptInterface(hardwareBridge, "Android")

                // Cold-start fallback: make NFC usable immediately, before the
                // Laravel/WebView hardware sync has completed. The existing
                // Laravel configuration/switching logic remains unchanged.
                hardwareRegistry.discover()
                    .firstOrNull { it.type == HardwareType.NFC_READER && it.available }
                    ?.let { reader ->
                        RuntimeLog.add("nfc_startup", "reader=${reader.id}")
                        hardwareBridge.readNfc(reader.id)
                    }

                webView.post {
                    webView.evaluateJavascript(
                        "window.YchAndroid && window.YchAndroid.sync && window.YchAndroid.sync();",
                        null
                    )
                }
                hardwareChangeMonitor = HardwareChangeMonitor(applicationContext) {
                    if (::hardwareBridge.isInitialized) hardwareBridge.notifyHardwareChanged()
                    runOnUiThread { if (::diagnosticsText.isInitialized) updateDiagnostics() }
                }
                onLaravelHardwareConfigSnapshot(hardwareConfigService.load())
                continueStartup()
            }
        }
    }

    private fun continueStartup() {
        configureKioskPolicy()
        startWebWatchdog()
        scheduleDailyReboot()

        val settings = settingsService.load()
        if (!settings.setupComplete) {
            showFirstSetupDialog()
        } else {
            hideSystemKeyboard("startup")
            enterKioskMode()
            initializeTerminal()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::customerDisplayManager.isInitialized && !adminMode) {
            customerDisplayManager.onHostResume()
        }
        if (!adminMode && settingsService.load().setupComplete) {
            hideSystemKeyboard("resume")
            enterKioskMode()
        }

        // Nach Boot/Activity-Start den in Laravel gespeicherten NFC-Reader
        // aktiv starten. Dadurch ist NFC nicht mehr vom Timing des WebView-
        // Hardware-Syncs abhaengig.
        if (
            !adminMode
            && ::hardwareBridge.isInitialized
            && ::hardwareConfigService.isInitialized
        ) {
            hardwareConfigService.load().nfcReaderId?.let { readerId ->
                hardwareBridge.readNfc(readerId)
            }
        }

        if (::diagnosticsText.isInitialized && ::identityService.isInitialized) {
            updateDiagnostics()
        }
    }

    override fun onPause() {
        if (::customerDisplayManager.isInitialized) {
            customerDisplayManager.onHostPause()
        }
        super.onPause()
    }

    private fun bindViews() {
        webView = findViewById(R.id.webView)
        statusOverlay = findViewById(R.id.statusOverlay)
        statusProgress = findViewById(R.id.statusProgress)
        statusTitle = findViewById(R.id.statusTitle)
        statusMessage = findViewById(R.id.statusMessage)
        statusPrimaryButton = findViewById(R.id.statusPrimaryButton)
        diagnosticsPanel = findViewById(R.id.diagnosticsPanel)
        diagnosticsText = findViewById(R.id.diagnosticsText)
        statusServerValue = findViewById(R.id.statusServerValue)
        statusTerminalValue = findViewById(R.id.statusTerminalValue)
        statusReaderValue = findViewById(R.id.statusReaderValue)

        statusPrimaryButton.setOnClickListener {
            primaryAction?.invoke()
        }
    }

    private fun configureWebView() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            webView.settings.safeBrowsingEnabled = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return true
                return !isAllowedWebUri(uri)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                lastMainFrameHttpError = null
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (lastMainFrameHttpError == null && terminalActive && !maintenanceMode) {
                    webLoaded = true

                    if (!adminMode) {
                        hideStatusOverlay()
                    }

                    // Die Laravel-Seite kann bereits geladen sein, bevor die
                    // Android-Bridge vollständig bereit war. Nach jedem
                    // erfolgreichen Seitenaufbau den Hardware-Sync erneut
                    // anstoßen.
                    view?.post {
                        view.evaluateJavascript(
                            "window.YchAndroid && window.YchAndroid.sync && window.YchAndroid.sync();",
                            null
                        )
                    }
                }

                updateDiagnostics()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    lastMainFrameHttpError = -1
                    webLoaded = false
                    handleWebViewConnectionError(
                        error?.description?.toString() ?: "WebView-Verbindungsfehler"
                    )
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request?.isForMainFrame != true) return

                val code = errorResponse?.statusCode ?: return
                lastMainFrameHttpError = code
                webLoaded = false

                when (code) {
                    503 -> showMaintenanceMode()
                    401, 403 -> {
                        terminalActive = false
                        stopHeartbeat()
                        readerService.stop()
                        initializeTerminal()
                    }
                    else -> {
                        handleWebViewConnectionError("WebView HTTP $code")
                    }
                }
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                handler?.cancel()
                lastMainFrameHttpError = -2
                webLoaded = false
                lastError = "TLS-/Zertifikatsfehler: ${error?.primaryError}"
                showOffline("Die sichere Verbindung zum Server konnte nicht hergestellt werden.")
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                webLoaded = false
                lastError = "WebView-Prozess wurde beendet."
                mainHandler.postDelayed({ recreate() }, 500)
                return true
            }
        }

        webView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                registerAdminGesture(view, event)
                if (!adminMode) {
                    mainHandler.postDelayed({ hideSystemKeyboard("webview_touch") }, 80)
                    mainHandler.postDelayed({ hideSystemKeyboard("webview_touch_retry") }, 250)
                }
            }
            false
        }

        webView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !adminMode) {
                mainHandler.postDelayed({ hideSystemKeyboard("webview_focus") }, 50)
            }
        }

        window.decorView.setOnSystemUiVisibilityChangeListener {
            if (!adminMode && settingsService.load().setupComplete) {
                mainHandler.post { hideSystemBars() }
            }
        }
    }

    private fun isAllowedWebUri(uri: Uri): Boolean {
        val settings = settingsService.load()
        val configured = runCatching { Uri.parse(settings.serverUrl) }.getOrNull() ?: return false

        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.host.equals(configured.host, ignoreCase = true)
    }

    private fun registerAdminGesture(view: View, event: MotionEvent) {
        if (!settingsService.load().setupComplete || adminMode || adminDialogOpen) return

        val triggerSize = 96f * resources.displayMetrics.density
        val inTopRight = event.x >= view.width - triggerSize && event.y <= triggerSize
        if (!inTopRight) {
            adminTapCount = 0
            return
        }

        val now = System.currentTimeMillis()
        if (adminTapCount == 0 || now - adminTapStartedAt > 4_000) {
            adminTapCount = 1
            adminTapStartedAt = now
        } else {
            adminTapCount++
        }

        if (adminTapCount >= 5) {
            adminTapCount = 0
            adminDialogOpen = true
            showAdminPinDialog()
        }
    }

    private fun initializeTerminal() {
        showStatus(
            title = "Terminal wird geprüft",
            message = "Registrierung und Serververbindung werden geprüft …",
            showProgress = true
        )

        networkExecutor.execute {
            val settings = settingsService.load()
            val result = apiService.getRegistrationStatus(settings)

            runOnUiThread {
                when {
                    result.transportError -> handleStartupOffline("Der Server ist nicht erreichbar.")
                    result.statusCode == 503 -> showMaintenanceMode()
                    result.value == "active" -> activateTerminal()
                    result.value != null -> showRegistrationState(result.value)
                    result.statusCode == 401 || result.statusCode == 403 || result.statusCode == 404 ->
                        showUnregisteredTerminal()
                    else -> showRegistrationState("unknown")
                }
            }
        }
    }

    private fun activateTerminal() {
        if (!terminalActive) {
            terminalActive = true
            // NFC is started only when Laravel explicitly selects a reader via Android.readNfc(readerId).
            startHeartbeat()
        }

        stopRegistrationPolling()
        maintenanceMode = false
        loadWebSession()
        updateDiagnostics()
    }

    private fun deactivateTerminal(status: String?) {
        terminalActive = false
        clearWebSession(clearCookies = false)
        // Heartbeat bleibt aktiv, damit eine Freigabe im Backend
        // innerhalb von maximal 10 Sekunden erkannt wird.
        readerService.stop()
        if (::internalNfcService.isInitialized) internalNfcService.stop()

        val currentStatus = status ?: "unknown"
        showStatus(
            title = "Terminal gesperrt",
            message = "Laravel meldet den Terminalstatus: $currentStatus\n\nDie Freigabe wird automatisch innerhalb von maximal 10 Sekunden erkannt.",
            buttonText = "Status prüfen",
            showProgress = false,
            action = { initializeTerminal() }
        )
        updateDiagnostics()
    }

    private fun showUnregisteredTerminal() {
        terminalActive = false
        clearWebSession(clearCookies = false)
        stopHeartbeat()
        readerService.stop()
        if (::internalNfcService.isInitialized) internalNfcService.stop()

        val terminalId = identityService.terminalUuid
        val name = settingsService.load().terminalName

        showStatus(
            title = "Terminal registrieren",
            message = "Terminal: $name\nID: $terminalId\n\nDas Tablet ist noch nicht im Laravel-Backend freigegeben.",
            buttonText = "Registrierung senden",
            showProgress = false,
            action = { registerTerminal() }
        )
    }

    private fun registerTerminal() {
        showStatus(
            title = "Registrierung wird gesendet",
            message = "Bitte warten …",
            showProgress = true
        )

        networkExecutor.execute {
            val settings = settingsService.load()
            val result = apiService.registerTerminal(settings)

            runOnUiThread {
                when {
                    result.transportError -> showOffline("Die Registrierung konnte nicht gesendet werden.")
                    result.statusCode == 503 -> showMaintenanceMode()
                    result.isSuccessful -> {
                        showRegistrationState("pending")
                        startRegistrationPolling()
                    }
                    else -> {
                        lastError = "Registrierung HTTP ${result.statusCode}: ${result.rawBody}"
                        showStatus(
                            title = "Registrierung fehlgeschlagen",
                            message = "Laravel hat die Registrierung abgelehnt (HTTP ${result.statusCode}).",
                            buttonText = "Erneut versuchen",
                            showProgress = false,
                            action = { registerTerminal() }
                        )
                    }
                }
            }
        }
    }

    private fun showRegistrationState(status: String) {
        terminalActive = false
        clearWebSession(clearCookies = false)
        stopHeartbeat()
        readerService.stop()

        val normalized = status.lowercase(Locale.ROOT)
        when (normalized) {
            "pending" -> {
                showStatus(
                    title = "Freigabe ausstehend",
                    message = "Terminal ${settingsService.load().terminalName}\nID: ${identityService.terminalUuid}\n\nBitte im Laravel-Backend freigeben. Der Status wird automatisch geprüft.",
                    buttonText = "Jetzt prüfen",
                    showProgress = true,
                    action = { initializeTerminal() }
                )
                startRegistrationPolling()
            }

            "rejected", "blocked", "disabled", "inactive" -> {
                stopRegistrationPolling()
                if (normalized == "disabled" || normalized == "inactive" || normalized == "blocked") {
                    startHeartbeat()
                }
                showStatus(
                    title = "Terminal gesperrt",
                    message = "Laravel meldet den Terminalstatus: $status\n\nDie Freigabe muss im Backend geändert werden.",
                    buttonText = "Status prüfen",
                    showProgress = false,
                    action = { initializeTerminal() }
                )
            }

            else -> {
                stopRegistrationPolling()
                showStatus(
                    title = "Terminal nicht freigegeben",
                    message = "Terminalstatus: $status\nID: ${identityService.terminalUuid}",
                    buttonText = "Registrierung senden",
                    showProgress = false,
                    action = { registerTerminal() }
                )
            }
        }
    }

    private fun startRegistrationPolling() {
        if (registrationPollFuture?.isCancelled == false && registrationPollFuture?.isDone == false) return

        registrationPollFuture = scheduler.scheduleWithFixedDelay(
            {
                if (terminalActive) return@scheduleWithFixedDelay
                networkExecutor.execute {
                    val result = apiService.getRegistrationStatus(settingsService.load())
                    if (result.value == "active") {
                        runOnUiThread { activateTerminal() }
                    } else if (result.statusCode == 503) {
                        runOnUiThread { showMaintenanceMode() }
                    }
                }
            },
            5,
            5,
            TimeUnit.SECONDS
        )
    }

    private fun stopRegistrationPolling() {
        registrationPollFuture?.cancel(false)
        registrationPollFuture = null
    }

    private fun loadWebSession() {
        if (!terminalActive || !openingWebSession.compareAndSet(false, true)) return

        showStatus(
            title = "Kasse wird geladen",
            message = "Sichere Terminal-Websession wird erstellt …",
            showProgress = true
        )

        networkExecutor.execute {
            val settings = settingsService.load()
            val result = apiService.createWebSessionUrl(settings)

            openingWebSession.set(false)

            runOnUiThread {
                when {
                    result.transportError -> handleStartupOffline("Die Kassen-Websession konnte nicht erstellt werden.")
                    result.statusCode == 503 -> showMaintenanceMode()
                    result.statusCode == 401 || result.statusCode == 403 -> initializeTerminal()
                    result.value.isNullOrBlank() -> {
                        lastError = "Websession HTTP ${result.statusCode}: ${result.rawBody}"
                        showOffline("Laravel hat keine gültige Terminal-Websession geliefert.")
                    }
                    else -> {
                        val uri = Uri.parse(result.value)
                        if (!isAllowedWebUri(uri)) {
                            lastError = "Ungültige Websession-URL: ${result.value}"
                            showOffline("Laravel hat eine nicht erlaubte Websession-URL geliefert.")
                            return@runOnUiThread
                        }

                        maintenanceMode = false
                        lastMainFrameHttpError = null
                        webView.loadUrl(result.value)
                    }
                }
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatFuture?.isCancelled == false && heartbeatFuture?.isDone == false) return

        heartbeatFuture = scheduler.scheduleWithFixedDelay(
            {
                networkExecutor.execute {
                    val result = apiService.sendHeartbeat(
                        settings = settingsService.load(),
                        readerName = readerService.readerName ?: "",
                        readerStatus = readerService.status,
                        appVersion = appVersion(),
                        appVersionCode = appVersionCode()
                    )

                    when {
                        result.transportError -> {
                            lastServerState = ApiService.ServerState.OFFLINE
                            runOnUiThread { updateDiagnostics() }
                        }
                        result.statusCode == 503 -> runOnUiThread { showMaintenanceMode() }
                        result.statusCode == 401 || result.statusCode == 403 -> {
                            runOnUiThread { checkTerminalAuthorization() }
                        }
                        result.isSuccessful -> {
                            val previous = lastServerState
                            lastServerState = ApiService.ServerState.ONLINE
                            handleHeartbeatUpdate(result.rawBody)
                            runOnUiThread {
                                if (previous != ApiService.ServerState.ONLINE && !webLoaded && !adminMode) {
                                    recoverWebView("heartbeat_online")
                                }
                                updateDiagnostics()
                            }
                        }
                        else -> runOnUiThread { checkTerminalAuthorization() }
                    }
                }
            },
            5,
            10,
            TimeUnit.SECONDS
        )
    }

    private fun handleHeartbeatUpdate(rawBody: String) {
        if (rawBody.isBlank()) return

        runCatching {
            val response = org.json.JSONObject(rawBody)

            val terminalStatus = response.optString("terminal_status", "unknown").trim()
            if (terminalStatus == "active" && !terminalActive) {
                runOnUiThread { activateTerminal() }
            } else if (terminalStatus.isNotBlank() && terminalStatus != "active" && terminalActive) {
                runOnUiThread { deactivateTerminal(terminalStatus) }
            }

            val configFingerprint = response.optString("config_fingerprint", "").trim()
            if (configFingerprint.isNotBlank()) {
                val previousFingerprint = lastConfigFingerprint
                lastConfigFingerprint = configFingerprint
                if (previousFingerprint != null && previousFingerprint != configFingerprint) {
                    hardwareBridge.notifyRuntimeSync()
                }
            }

            val update = when {
                response.optJSONObject("update") != null -> response.getJSONObject("update")
                response.optJSONObject("app_update") != null -> response.getJSONObject("app_update")
                else -> null
            } ?: return

            val versionCode = update.optLong("versionCode", update.optLong("version_code", -1L))
            val apkUrl = update.optString("apkUrl", update.optString("apk_url", "")).trim()
            val sha256 = update.optString("sha256", "").trim()

            if (::apkUpdateManager.isInitialized && versionCode > appVersionCode() && apkUrl.isNotBlank() && sha256.isNotBlank()) {
                apkUpdateManager.install(
                    org.json.JSONObject()
                        .put("versionCode", versionCode)
                        .put("apkUrl", apkUrl)
                        .put("sha256", sha256)
                )
            }
        }.onFailure { RuntimeLog.add("heartbeat_update", it.message ?: it.javaClass.simpleName) }
    }

    private fun appVersionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun stopHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    private fun checkTerminalAuthorization() {
        networkExecutor.execute {
            val result = apiService.getRegistrationStatus(settingsService.load())
            runOnUiThread {
                when {
                    result.value == "active" -> Unit
                    result.transportError -> Unit
                    result.statusCode == 503 -> showMaintenanceMode()
                    else -> deactivateTerminal(result.value)
                }
            }
        }
    }

    private fun checkServer(showResultToast: Boolean) {
        if (!checkingServer.compareAndSet(false, true)) return

        networkExecutor.execute {
            val result = apiService.sendHeartbeat(
                settings = settingsService.load(),
                readerName = readerService.readerName ?: "",
                readerStatus = readerService.status,
                appVersion = appVersion(),
                appVersionCode = appVersionCode()
            )
            checkingServer.set(false)

            val previous = lastServerState
            val state = when {
                result.statusCode == 503 -> ApiService.ServerState.MAINTENANCE
                result.isSuccessful -> ApiService.ServerState.ONLINE
                else -> ApiService.ServerState.OFFLINE
            }
            lastServerState = state
            if (result.isSuccessful) handleHeartbeatUpdate(result.rawBody)

            runOnUiThread {
                when (state) {
                    ApiService.ServerState.MAINTENANCE -> showMaintenanceMode()
                    ApiService.ServerState.OFFLINE -> {
                        if (!adminMode) handleStartupOffline("Der Laravel-Server ist nicht erreichbar.")
                    }
                    ApiService.ServerState.ONLINE -> {
                        maintenanceMode = false

                        when {
                            showResultToast && terminalActive && !webLoaded && !adminMode -> {
                                recoverWebView("manual_heartbeat_online")
                            }
                            previous != ApiService.ServerState.ONLINE &&
                                settingsService.load().setupComplete -> {
                                initializeTerminal()
                            }
                        }
                    }
                }

                if (showResultToast) {
                    val text = when (state) {
                        ApiService.ServerState.ONLINE -> "Server erreichbar"
                        ApiService.ServerState.MAINTENANCE -> "Server im Wartungsmodus"
                        ApiService.ServerState.OFFLINE -> "Server nicht erreichbar"
                    }
                    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                }

                updateDiagnostics()
            }
        }
    }


    private fun handleWebViewConnectionError(message: String) {
        if (maintenanceMode || adminMode || !terminalActive) return

        lastError = message

        // A WebView transport error (especially directly after display sleep/resume)
        // does not prove that Laravel is offline. Confirm reachability through the
        // API first and only show the offline overlay for a confirmed outage.
        if (!checkingServer.compareAndSet(false, true)) return

        networkExecutor.execute {
            val result = apiService.sendHeartbeat(
                settings = settingsService.load(),
                readerName = readerService.readerName ?: "",
                readerStatus = readerService.status,
                appVersion = appVersion(),
                appVersionCode = appVersionCode()
            )
            checkingServer.set(false)

            val state = when {
                result.statusCode == 503 -> ApiService.ServerState.MAINTENANCE
                result.isSuccessful -> ApiService.ServerState.ONLINE
                else -> ApiService.ServerState.OFFLINE
            }
            val previous = lastServerState
            lastServerState = state
            if (result.isSuccessful) handleHeartbeatUpdate(result.rawBody)

            runOnUiThread {
                when (state) {
                    ApiService.ServerState.ONLINE -> {
                        maintenanceMode = false
                        if (!webRecoveryInProgress) {
                            recoverWebView("webview_error_server_online")
                        }
                    }
                    ApiService.ServerState.MAINTENANCE -> showMaintenanceMode()
                    ApiService.ServerState.OFFLINE -> {
                        showOffline("Der Laravel-Server ist nicht erreichbar.")
                    }
                }

                if (previous != state) updateDiagnostics()
            }
        }
    }

    private fun handleStartupOffline(message: String) {
        if (android.os.SystemClock.elapsedRealtime() < startupGraceUntil) {
            showStatus(
                title = "Kassa wird gestartet",
                message = "Netzwerk und Server werden vorbereitet …",
                showProgress = true
            )
            return
        }
        showOffline(message)
    }

    /**
     * WebView watchdog. It never reloads while Laravel reports an active sale/payment.
     * Server reachability remains the source of truth; this only recovers a stale WebView.
     */
    private fun startWebWatchdog() {
        if (webWatchdogFuture?.isCancelled == false && webWatchdogFuture?.isDone == false) return

        webWatchdogFuture = scheduler.scheduleWithFixedDelay(
            {
                if (adminMode || maintenanceMode || !terminalActive || lastServerState != ApiService.ServerState.ONLINE) {
                    return@scheduleWithFixedDelay
                }

                runOnUiThread {
                    if (webRecoveryInProgress) return@runOnUiThread

                    if (!webLoaded) {
                        webWatchdogFailures++
                        if (webWatchdogFailures >= 2) {
                            recoverWebView("not_loaded")
                        }
                        return@runOnUiThread
                    }

                    webView.evaluateJavascript(
                        "(function(){try{return JSON.stringify({ready:document.readyState==='complete',busy:!!document.querySelector('[data-kasse-busy=\"1\"],[data-kasse-active=\"1\"]'),body:!!document.body});}catch(e){return JSON.stringify({ready:false,busy:true,body:false});}})();"
                    ) { raw ->
                        val decoded = raw?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: ""
                        val healthy = decoded.contains("\"ready\":true") && decoded.contains("\"body\":true")
                        val busy = decoded.contains("\"busy\":true")

                        if (healthy) {
                            webWatchdogFailures = 0
                        } else if (!busy) {
                            webWatchdogFailures++
                            if (webWatchdogFailures >= 2) recoverWebView("healthcheck_failed")
                        }
                    }
                }
            },
            20,
            15,
            TimeUnit.SECONDS
        )
    }

    private fun recoverWebView(reason: String) {
        if (webRecoveryInProgress || adminMode || maintenanceMode || !terminalActive) return
        webRecoveryInProgress = true
        webWatchdogFailures = 0
        RuntimeLog.add("webview_recovery", reason)

        webView.evaluateJavascript(
            "(function(){return !!document.querySelector('[data-kasse-busy=\"1\"],[data-kasse-active=\"1\"]');})();"
        ) { busyRaw ->
            if (busyRaw == "true") {
                webRecoveryInProgress = false
                return@evaluateJavascript
            }

            webLoaded = false
            webView.stopLoading()
            loadWebSession()
            mainHandler.postDelayed({ webRecoveryInProgress = false }, 5_000L)
        }
    }

    private fun scheduleDailyReboot() {
        dailyRebootFuture?.cancel(false)

        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }

        val delayMs = (next.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
        RuntimeLog.add("daily_reboot_scheduled", next.time.toString())

        dailyRebootFuture = scheduler.schedule(
            { runOnUiThread { attemptDailyReboot() } },
            delayMs,
            TimeUnit.MILLISECONDS
        )
    }

    private fun attemptDailyReboot() {
        if (adminMode) {
            deferDailyReboot("admin_mode")
            return
        }

        webView.evaluateJavascript(
            "(function(){return !!document.querySelector('[data-kasse-busy=\"1\"],[data-kasse-active=\"1\"]');})();"
        ) { busyRaw ->
            if (busyRaw == "true") {
                deferDailyReboot("active_sale")
                return@evaluateJavascript
            }

            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(packageName) || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                RuntimeLog.add("daily_reboot_skipped", "device_owner_required")
                scheduleDailyReboot()
                return@evaluateJavascript
            }

            val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
            RuntimeLog.add("daily_reboot", "05:00")
            runCatching { dpm.reboot(admin) }
                .onFailure {
                    RuntimeLog.add("daily_reboot_failed", it.message ?: it.javaClass.simpleName)
                    scheduleDailyReboot()
                }
        }
    }

    private fun deferDailyReboot(reason: String) {
        RuntimeLog.add("daily_reboot_deferred", reason)
        dailyRebootFuture = scheduler.schedule(
            { runOnUiThread { attemptDailyReboot() } },
            5,
            TimeUnit.MINUTES
        )
    }

    private fun clearWebSession(clearCookies: Boolean) {
        webLoaded = false
        webView.stopLoading()
        webView.loadUrl("about:blank")
        if (clearCookies) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    private fun showMaintenanceMode() {
        maintenanceMode = true
        webLoaded = false
        lastServerState = ApiService.ServerState.MAINTENANCE

        showStatus(
            title = "Wartungsmodus",
            message = "Die Kasse ist derzeit wegen Wartungsarbeiten nicht verfügbar. Die App prüft automatisch, wann der Betrieb wieder möglich ist.",
            buttonText = "Jetzt prüfen",
            showProgress = true,
            action = { checkServer(showResultToast = true) }
        )
        updateDiagnostics()
    }

    private fun showOffline(message: String) {
        if (maintenanceMode) return
        webLoaded = false

        showStatus(
            title = "Verbindung unterbrochen",
            message = message + "\n\nDie Verbindung wird automatisch erneut geprüft.",
            buttonText = "Erneut versuchen",
            showProgress = false,
            action = { checkServer(showResultToast = true) }
        )
        updateDiagnostics()
    }

    private fun showStatus(
        title: String,
        message: String,
        buttonText: String? = null,
        showProgress: Boolean,
        action: (() -> Unit)? = null
    ) {
        statusTitle.text = title
        statusMessage.text = message
        statusProgress.visibility = if (showProgress) View.VISIBLE else View.GONE
        primaryAction = action

        if (buttonText.isNullOrBlank() || action == null) {
            statusPrimaryButton.visibility = View.GONE
        } else {
            statusPrimaryButton.text = buttonText
            statusPrimaryButton.visibility = View.VISIBLE
        }

        statusOverlay.visibility = View.VISIBLE
    }

    private fun hideStatusOverlay() {
        if (adminMode) return
        statusOverlay.visibility = View.GONE
    }

    override fun onReaderStatusChanged(status: NfcReaderStatus, readerName: String?) {
        RuntimeLog.add("nfc_status", "$status/${readerName ?: ""}")
        if (::hardwareBridge.isInitialized) hardwareBridge.notifyHardwareChanged()
        runOnUiThread { updateDiagnostics() }
    }

    override fun onCardPresented(uid: String, readerName: String) {
        lastUid = uid
        runOnUiThread { updateDiagnostics() }

        val configured = if (::hardwareConfigService.isInitialized) {
            hardwareConfigService.load().nfcReaderId ?: "unknown"
        } else {
            "unknown"
        }

        RuntimeLog.add("nfc_presented", "$configured/$readerName")

        if (terminalActive && ::apiService.isInitialized && sendingUid.compareAndSet(false, true)) {
            networkExecutor.execute {
                val result = apiService.sendUid(
                    settings = settingsService.load(),
                    uid = uid,
                    reader = configured
                )

                lastTransmissionAt = Date()
                lastTransmissionSuccessful = result.isSuccessful && result.value == true
                lastError = if (lastTransmissionSuccessful == true) {
                    null
                } else {
                    result.transportError.toString().takeIf { result.transportError }
                        ?: "NFC-Uebertragung HTTP ${result.statusCode}: ${result.rawBody}"
                }

                RuntimeLog.add(
                    "nfc_transmission",
                    "status=${result.statusCode};success=${lastTransmissionSuccessful};transportError=${result.transportError}"
                )

                sendingUid.set(false)
                runOnUiThread { updateDiagnostics() }
            }
        }

        if (::hardwareBridge.isInitialized) {
            hardwareBridge.notifyNfc(uid, configured, readerName)
        }
    }

    override fun onCardRemoved(readerName: String) {
        runOnUiThread { updateDiagnostics() }
        if (::hardwareBridge.isInitialized) {
            hardwareBridge.notifyNfcRemoved(hardwareConfigService.load().nfcReaderId ?: "unknown", readerName)
        }
    }

    private fun configureDiagnosticsButtons() {
        findViewById<Button>(R.id.buttonHardwareOverview).setOnClickListener {
            showHardwareOverviewDialog()
        }

        findViewById<Button>(R.id.buttonHardwareSelfTest).setOnClickListener {
            showHardwareSelfTestDialog()
        }

        findViewById<Button>(R.id.buttonReloadCashRegister).setOnClickListener {
            if (terminalActive && webLoaded) {
                webView.reload()
            } else if (terminalActive) {
                loadWebSession()
            } else {
                initializeTerminal()
            }
        }

        findViewById<Button>(R.id.buttonSettings).setOnClickListener {
            showConfigurationDialog()
        }

        findViewById<Button>(R.id.buttonCloseDiagnostics).setOnClickListener {
            closeDiagnostics()
        }

        findViewById<Button>(R.id.buttonExitApp).setOnClickListener {
            leaveDedicatedModeForAdmin()
            finishAndRemoveTask()
        }
    }

    private fun showAdminPinDialog() {
        exitKioskMode()

        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((28 * density).toInt(), (8 * density).toInt(), (28 * density).toInt(), 0)
        }

        val input = EditText(this).apply {
            hint = "Admin-PIN"
            textSize = 20f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
        }
        container.addView(input, matchWidthParams())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Admin-Zugang")
            .setMessage("PIN eingeben, um Diagnose und Einstellungen zu öffnen.")
            .setView(container)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Entsperren", null)
            .create()

        dialog.setOnDismissListener {
            adminDialogOpen = false
            adminTapCount = 0
            if (!adminMode && settingsService.load().setupComplete) {
                hideSystemKeyboard("admin_pin_dismiss")
                enterKioskMode()
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.78f).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            showSystemKeyboard(input, "admin_pin")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.isBlank()) {
                    input.error = "PIN eingeben"
                    return@setOnClickListener
                }

                // PBKDF2 niemals auf dem UI-Thread ausführen.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                networkExecutor.execute {
                    val valid = settingsService.verifyAdminPin(pin)
                    runOnUiThread {
                        if (valid) {
                            dialog.dismiss()
                            showDiagnostics()
                        } else {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            input.error = "PIN falsch"
                            input.selectAll()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDiagnostics() {
        adminMode = true
        if (::customerDisplayManager.isInitialized) {
            customerDisplayManager.blank()
        }
        exitKioskMode()
        diagnosticsPanel.visibility = View.VISIBLE
        updateDiagnostics()
    }

    private fun closeDiagnostics() {
        diagnosticsPanel.visibility = View.GONE
        adminMode = false
        hideSystemKeyboard("diagnostics_closed")

        if (terminalActive && webLoaded && !maintenanceMode) {
            hideStatusOverlay()
        } else if (settingsService.load().setupComplete) {
            initializeTerminal()
        }

        enterKioskMode()

        if (::customerDisplayManager.isInitialized) {
            customerDisplayManager.onHostResume()
        }
    }

    private fun updateDiagnostics() {
        if (!::diagnosticsText.isInitialized) return

        val settings = settingsService.load()
        val transmission = when (lastTransmissionSuccessful) {
            true -> "Erfolgreich"
            false -> "Fehlgeschlagen"
            null -> "Noch keine"
        }

        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        statusServerValue.text = when (lastServerState) {
            ApiService.ServerState.ONLINE -> "Online"
            ApiService.ServerState.MAINTENANCE -> "Wartung"
            ApiService.ServerState.OFFLINE -> "Offline"
        }
        statusTerminalValue.text = if (terminalActive) "Freigegeben" else "Nicht aktiv"
        statusReaderValue.text = if (::hardwareRegistry.isInitialized && ::hardwareConfigService.isInitialized) {
            val devices = hardwareRegistry.discover()
            val cfg = hardwareConfigService.load()
            val configured = listOfNotNull(cfg.nfcReaderId, cfg.paymentProviderId, cfg.printerId, cfg.customerDisplayId)
            val missing = configured.count { id -> devices.none { it.id == id && it.available } }
            when {
                configured.isEmpty() -> "Nicht konfiguriert"
                missing == 0 -> "Bereit"
                else -> "$missing fehlt"
            }
        } else "–"

        diagnosticsText.text = buildString {
            appendLine("SYSTEM")
            appendLine("NFC:              ${if (::hardwareConfigService.isInitialized) hardwareConfigService.load().nfcReaderId ?: "Nicht aktiv" else "–"}")
            appendLine("Bondrucker:       ${if (::sunmiPrinterManager.isInitialized && sunmiPrinterManager.isReady()) "Bereit" else "Nicht bereit"}")
            appendLine("Kundendisplay:    ${if (::hardwareConfigService.isInitialized) hardwareConfigService.load().customerDisplayId ?: "Aus / nicht konfiguriert" else "–"}")
            if (::hardwareConfigService.isInitialized) {
                val hw = hardwareConfigService.load()
                appendLine("SumUp:            ${hw.paymentProviderId ?: "Laravel / externer Reader"}")
            }
            appendLine("App:              ${appVersion()} · Bridge v${LaravelHardwareBridge.BRIDGE_VERSION}")
            appendLine()
            appendLine("LETZTER VORGANG")
            appendLine("UID:              ${lastUid ?: "Keine"}")
            appendLine("Übertragung:      ${lastTransmissionAt?.let { dateFormat.format(it) } ?: "Keine"}")
            appendLine("Status:           $transmission")
            appendLine("Letzter Fehler:   ${lastError ?: "Keiner"}")
            appendLine()
            appendLine("TECHNISCHE DETAILS")
            appendLine("Terminal:         ${settings.terminalName}")
            appendLine("Terminal-ID:      ${identityService.terminalUuid}")
            appendLine("WebView:          ${if (webLoaded) "Geladen" else "Nicht geladen"}")
            appendLine("Device Owner:     ${if (dpm.isDeviceOwnerApp(packageName)) "Ja" else "Nein"}")
            appendLine("Kiosk:            ${lockTaskText(activityManager.lockTaskModeState)}")
            if (::apkUpdateManager.isInitialized) appendLine("Update:           ${apkUpdateManager.status().optString("state", "idle")}")
        }
    }

    private fun lockTaskText(state: Int): String = when (state) {
        ActivityManager.LOCK_TASK_MODE_LOCKED -> "Kiosk/Locked"
        ActivityManager.LOCK_TASK_MODE_PINNED -> "Angeheftet"
        else -> "Aus"
    }

    private fun showFirstSetupDialog() {
        exitKioskMode()

        val settings = settingsService.load()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }

        val serverInput = EditText(this).apply {
            hint = "Server-URL"
            setText(settings.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val terminalInput = EditText(this).apply {
            hint = "Terminalname"
            setText(settings.terminalName)
        }
        val pinInput = EditText(this).apply {
            hint = "Admin-PIN (mindestens 4 Zeichen)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val pinConfirmInput = EditText(this).apply {
            hint = "Admin-PIN wiederholen"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        container.addView(serverInput, matchWidthParams())
        container.addView(terminalInput, matchWidthParams())
        container.addView(pinInput, matchWidthParams())
        container.addView(pinConfirmInput, matchWidthParams())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Huettenbuch einrichten")
            .setMessage("Einmalige Gerätekonfiguration")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Speichern", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            showSystemKeyboard(serverInput, "first_setup")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val server = serverInput.text.toString().trim()
                val terminal = terminalInput.text.toString().trim()
                val pin = pinInput.text.toString()
                val confirm = pinConfirmInput.text.toString()

                when {
                    !server.startsWith("https://", ignoreCase = true) ->
                        serverInput.error = "Nur HTTPS ist erlaubt"
                    terminal.isBlank() -> terminalInput.error = "Terminalname fehlt"
                    pin.length < 4 -> pinInput.error = "Mindestens 4 Zeichen"
                    pin != confirm -> pinConfirmInput.error = "PIN stimmt nicht überein"
                    else -> {
                        settingsService.completeSetup(server, terminal, pin)
                        dialog.dismiss()
                        hideSystemKeyboard("first_setup_saved")
                        enterKioskMode()
                        initializeTerminal()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showConfigurationDialog() {
        val settings = settingsService.load()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }

        val serverInput = EditText(this).apply {
            hint = "Server-URL"
            setText(settings.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val terminalInput = EditText(this).apply {
            hint = "Terminalname"
            setText(settings.terminalName)
        }
        val newPinInput = EditText(this).apply {
            hint = "Neuer Admin-PIN (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(serverInput, matchWidthParams())
        container.addView(terminalInput, matchWidthParams())
        container.addView(newPinInput, matchWidthParams())

        val dialog = AlertDialog.Builder(this)
            .setTitle("Konfiguration")
            .setView(container)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern", null)
            .create()

        dialog.setOnDismissListener {
            if (adminMode) hideSystemKeyboard("configuration_dismiss")
        }

        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            showSystemKeyboard(serverInput, "configuration")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val server = serverInput.text.toString().trim()
                val terminal = terminalInput.text.toString().trim()
                val newPin = newPinInput.text.toString()

                when {
                    !server.startsWith("https://", ignoreCase = true) ->
                        serverInput.error = "Nur HTTPS ist erlaubt"
                    terminal.isBlank() -> terminalInput.error = "Terminalname fehlt"
                    newPin.isNotBlank() && newPin.length < 4 ->
                        newPinInput.error = "Mindestens 4 Zeichen"
                    else -> {
                        settingsService.saveConfiguration(server, terminal)
                        if (newPin.isNotBlank()) settingsService.changeAdminPin(newPin)
                                        dialog.dismiss()

                        terminalActive = false
                        clearWebSession(clearCookies = true)
                        stopHeartbeat()
                        stopRegistrationPolling()
                        readerService.stop()
                        initializeTerminal()
                        updateDiagnostics()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun onLaravelHardwareConfigSnapshot(config: HardwareConfig) {
        RuntimeLog.add(
            "laravel_hardware_config",
            "nfc=${config.nfcReaderId}; printer=${config.printerId}; display=${config.customerDisplayId}; payment=${config.paymentProviderId}; paymentTerminal=${config.paymentTerminalId}"
        )

        // Laravel uses customerDisplayId=null for self-service. The web layer
        // does not call clearCustomerDisplay during sync, therefore Android
        // must enforce black here. Keep the Presentation alive to prevent
        // SUNMI from falling back to screen mirroring.
        if (::customerDisplayManager.isInitialized && config.customerDisplayId == null) {
            customerDisplayManager.blank()
        }

        // NFC muss auch beim Kaltstart aktiv werden. Der WebView-Sync bleibt
        // zusaetzlich bestehen, ist aber nicht mehr die einzige Startquelle.
        if (::hardwareBridge.isInitialized) {
            config.nfcReaderId?.let { readerId ->
                webView.post { hardwareBridge.readNfc(readerId) }
            }
        }

        updateDiagnostics()
    }

    private fun showHardwareOverviewDialog() {
        val devices = hardwareRegistry.discover()
        val config = hardwareConfigService.load()
        val configuredIds = setOfNotNull(config.nfcReaderId, config.paymentProviderId, config.printerId, config.customerDisplayId)
        fun status(label: String, id: String?): String {
            if (id.isNullOrBlank()) return "$label: Nicht konfiguriert"
            val d = devices.firstOrNull { it.id == id }
            return when {
                d == null -> "$label: $id  ✗ nicht erkannt"
                d.available -> "$label: ${d.name}\n  $id  ✓ bereit"
                else -> "$label: ${d.name}\n  $id  ✗ nicht verfügbar"
            }
        }
        val text = buildString {
            appendLine("Konfiguration: ausschließlich Laravel (nur Anzeige)")
            appendLine("Bridge: v${LaravelHardwareBridge.BRIDGE_VERSION}")
            appendLine()
            appendLine(status("Mitglieder-/NFC-Reader", config.nfcReaderId))
            appendLine()
            appendLine(status("Kartenzahlung", config.paymentProviderId))
            if (!config.paymentTerminalId.isNullOrBlank()) appendLine("Externer SumUp Reader: ${config.paymentTerminalId}  (Laravel / Cloud API; Android prueft ihn nicht)")
            appendLine()
            appendLine(status("Bondrucker", config.printerId))
            appendLine()
            appendLine(status("Kundendisplay", config.customerDisplayId))
            appendLine()
            appendLine("Weitere erkannte Hardware:")
            val other = devices.filter { it.id !in configuredIds }
            if (other.isEmpty()) appendLine("Keine") else other.forEach { appendLine("• ${it.name} [${it.id}] ${if (it.available) "✓" else "✗"}") }
        }
        AlertDialog.Builder(this)
            .setTitle("Hardware – ${settingsService.load().terminalName}")
            .setMessage(text)
            .setNegativeButton("Logs") { _, _ -> showLogsDialog() }
            .setNeutralButton("Neu suchen") { _, _ ->
                if (::hardwareBridge.isInitialized) hardwareBridge.notifyHardwareChanged()
                updateDiagnostics()
                mainHandler.postDelayed({ showHardwareOverviewDialog() }, 200)
            }
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun showHardwareSelfTestDialog() {
        val cfg = hardwareConfigService.load()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 12, 36, 4)
        }
        fun addAction(label: String, action: () -> String) {
            container.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    val result = runCatching { action() }.getOrElse { "Fehler: ${it.message}" }
                    Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
                    updateDiagnostics()
                }
            }, matchWidthParams())
        }
        addAction("Hardware neu erkennen") {
            hardwareBridge.notifyHardwareChanged()
            "Hardware neu erkannt"
        }
        container.addView(Button(this).apply {
            text = "NFC testen"
            setOnClickListener {
                val id = cfg.nfcReaderId
                if (id == null) {
                    Toast.makeText(this@MainActivity, "In Laravel ist kein NFC-Reader konfiguriert", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val start = org.json.JSONObject(hardwareBridge.testNfc(id))
                if (!start.optBoolean("success")) {
                    Toast.makeText(this@MainActivity, start.toString(), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                Toast.makeText(this@MainActivity, "Chip jetzt vorhalten – Testevents werden NICHT an die Kasse gesendet", Toast.LENGTH_LONG).show()
                val poll = object : Runnable {
                    override fun run() {
                        val st = org.json.JSONObject(hardwareBridge.getNfcTestStatus())
                        if (st.optBoolean("done")) {
                            val msg = if (st.optBoolean("success")) "NFC OK – UID: ${st.optString("uid")}" else "NFC-Test fehlgeschlagen: ${st.optString("code")}" 
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                            updateDiagnostics()
                        } else mainHandler.postDelayed(this, 300)
                    }
                }
                mainHandler.postDelayed(poll, 300)
            }
        }, matchWidthParams())
        addAction("Testbon drucken") {
            val id = cfg.printerId ?: return@addAction "In Laravel ist kein Drucker konfiguriert"
            hardwareBridge.testPrinter(id)
        }
        addAction("SumUp Tap-to-Pay Status") {
            val st = org.json.JSONObject(hardwareBridge.getPaymentStatus())
            when {
                !st.optBoolean("available", true) -> "SumUp Tap-to-Pay ist in diesem Build nicht enthalten. Externer SumUp Reader wird direkt durch Laravel verwendet."
                st.optBoolean("initialized") -> "SumUp Tap-to-Pay bereit (interner NFC)"
                st.optBoolean("initializing") -> "SumUp Tap-to-Pay wird initialisiert"
                else -> "SumUp Tap-to-Pay nicht initialisiert - Initialisierung erfolgt durch Laravel"
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Hardware-Selbsttest")
            .setMessage("Tests verwenden ausschließlich die in Laravel konfigurierte Hardware. Es wird keine Konfiguration geändert und keine Zahlung gestartet.")
            .setView(container)
            .setPositiveButton("Schließen", null)
            .show()
    }

    private fun showLogsDialog() {
        val text = RuntimeLog.json()
        AlertDialog.Builder(this)
            .setTitle("Android Runtime-Logs")
            .setMessage(if (text == "[]") "Keine Logeinträge" else text)
            .setNegativeButton("Leeren") { _, _ -> RuntimeLog.clear() }
            .setPositiveButton("Schließen", null)
            .show()
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (::hardwareBridge.isInitialized) hardwareBridge.notifyHardwareChanged()
    }

    private fun currentInputMethodId(): String =
        Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ).orEmpty()

    /**
     * Gboard bleibt dauerhaft die System-IME. Im Kassenbetrieb wird sie nur
     * ausgeblendet; die Laravel-Tastatur bleibt davon unberuehrt.
     */
    private fun hideSystemKeyboard(reason: String) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = currentFocus?.windowToken ?: webView.windowToken
        val hidden = runCatching { imm.hideSoftInputFromWindow(token, 0) }.getOrDefault(false)
        RuntimeLog.add(
            "keyboard_hide",
            "reason=$reason;ime=${currentInputMethodId()};result=$hidden;admin=$adminMode"
        )
    }

    /** Admin-Dialoge verwenden immer die bereits konfigurierte Systemtastatur (Gboard). */
    private fun showSystemKeyboard(target: View, reason: String) {
        target.requestFocus()
        target.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val shown = runCatching { imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT) }.getOrDefault(false)
            RuntimeLog.add(
                "keyboard_show",
                "reason=$reason;ime=${currentInputMethodId()};result=$shown;target=${target.javaClass.simpleName}"
            )
        }, 120)
    }

    private fun matchWidthParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun configureKioskPolicy() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return

        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        runCatching {
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            dpm.setStatusBarDisabled(admin, true)
            runCatching { dpm.setKeyguardDisabled(admin, true) }
            if (Build.VERSION.SDK_INT >= 28) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }

            val homeFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(
                admin,
                homeFilter,
                ComponentName(this, MainActivity::class.java)
            )
        }
    }

    private fun leaveDedicatedModeForAdmin() {
        exitKioskMode()

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (!dpm.isDeviceOwnerApp(packageName)) return

        val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
        runCatching {
            dpm.setStatusBarDisabled(admin, false)
            dpm.clearPackagePersistentPreferredActivities(admin, packageName)
        }
    }

    private fun enterKioskMode() {
        if (adminMode || !settingsService.load().setupComplete) return

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(packageName)) {
            val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
            runCatching { dpm.setStatusBarDisabled(admin, true) }
        }

        hideSystemBars()

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (
            activityManager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE &&
            dpm.isDeviceOwnerApp(packageName) &&
            dpm.isLockTaskPermitted(packageName)
        ) {
            runCatching { startLockTask() }
        }

        mainHandler.postDelayed({ hideSystemBars() }, 150)
    }

    private fun exitKioskMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isDeviceOwnerApp(packageName)) {
            val admin = ComponentName(this, KioskDeviceAdminReceiver::class.java)
            runCatching { dpm.setStatusBarDisabled(admin, false) }
        }

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { stopLockTask() }
        }

        ViewCompat.getWindowInsetsController(window.decorView)?.show(
            WindowInsetsCompat.Type.systemBars()
        )
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.getWindowInsetsController(window.decorView)?.hide(
            WindowInsetsCompat.Type.systemBars()
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !adminMode && settingsService.load().setupComplete) {
            enterKioskMode()
            mainHandler.postDelayed({ hideSystemBars() }, 100)
            mainHandler.postDelayed({ hideSystemKeyboard("window_focus") }, 80)
        }
    }

    @Deprecated("Handled deliberately for kiosk navigation")
    override fun onBackPressed() {
        when {
            adminMode -> closeDiagnostics()
            webView.canGoBack() -> webView.goBack()
            else -> Unit
        }
    }

    private fun appVersion(): String {
        @Suppress("DEPRECATION")
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    }

    override fun onDestroy() {
        heartbeatFuture?.cancel(true)
        registrationPollFuture?.cancel(true)
        webWatchdogFuture?.cancel(true)
        dailyRebootFuture?.cancel(true)

        if (::hardwareChangeMonitor.isInitialized) hardwareChangeMonitor.dispose()
        if (::apkUpdateManager.isInitialized) apkUpdateManager.dispose()
        if (::readerService.isInitialized) readerService.dispose()
        if (::internalNfcService.isInitialized) internalNfcService.stop()
        if (::sunmiPrinterManager.isInitialized) sunmiPrinterManager.dispose()
        if (::customerDisplayManager.isInitialized) customerDisplayManager.dispose()
        if (::sunmiNfcController.isInitialized) sunmiNfcController.dispose()
        scheduler.shutdownNow()
        networkExecutor.shutdownNow()

        webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }

        super.onDestroy()
    }
}
