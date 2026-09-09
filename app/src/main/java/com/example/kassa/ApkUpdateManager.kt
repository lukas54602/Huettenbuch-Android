package com.example.kassa

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Downloads and verifies a Laravel-provided APK, then hands it to Android PackageInstaller. */
class ApkUpdateManager(
    private val activity: Activity,
    private val onStatus: (JSONObject) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var state = JSONObject().put("state", "idle")
    private val action = "${activity.packageName}.UPDATE_INSTALL_STATUS"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val silentExpected = isDeviceOwner()
                RuntimeLog.add(
                    "app_update_install",
                    "pending_user_action;deviceOwner=$silentExpected;manufacturer=${Build.MANUFACTURER}"
                )
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(confirm)
                }
                publish(JSONObject().put("state", "awaiting_user_confirmation").put("success", true))
            } else if (status == PackageInstaller.STATUS_SUCCESS) {
                publish(JSONObject().put("state", "installed").put("success", true))
            } else {
                publish(JSONObject().put("state", "install_failed").put("success", false)
                    .put("status", status).put("message", message ?: "Installation fehlgeschlagen"))
            }
        }
    }

    init {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") activity.registerReceiver(receiver, filter)
    }

    fun status(): JSONObject = JSONObject(state.toString())

    fun install(request: JSONObject): JSONObject {
        val url = request.optString("apkUrl").trim()
        val sha256 = request.optString("sha256").trim().lowercase()
        val versionCode = request.optLong("versionCode", -1L)
        if (!url.startsWith("https://")) return error("UPDATE_URL_INVALID", "Nur HTTPS APK-URLs sind erlaubt")
        if (!sha256.matches(Regex("[0-9a-f]{64}"))) return error("UPDATE_SHA256_REQUIRED", "SHA-256 fehlt oder ist ungueltig")
        if (versionCode <= currentVersionCode()) return JSONObject().put("success", true).put("state", "already_current")
        if (state.optString("state") in setOf("downloading", "verifying", "installing")) return error("UPDATE_BUSY", "Update laeuft bereits")

        publish(JSONObject().put("success", true).put("state", "downloading").put("versionCode", versionCode))
        executor.execute {
            val apk = File(activity.cacheDir, "update-$versionCode.apk")
            try {
                download(url, apk)
                publish(JSONObject().put("success", true).put("state", "verifying").put("versionCode", versionCode))
                val actual = sha256(apk)
                require(actual.equals(sha256, ignoreCase = true)) { "SHA-256 stimmt nicht ueberein" }
                require(archivePackageName(apk) == activity.packageName) { "APK packageName stimmt nicht" }
                require(sameSigningCertificate(apk)) { "APK ist nicht mit dem installierten Release-Key signiert" }
                val strategy = if (isDeviceOwner()) "device_owner_package_installer" else "package_installer"
                publish(
                    JSONObject()
                        .put("success", true)
                        .put("state", "installing")
                        .put("versionCode", versionCode)
                        .put("strategy", strategy)
                        .put("deviceOwner", isDeviceOwner())
                        .put("manufacturer", Build.MANUFACTURER)
                )
                installWithPackageInstaller(apk)
            } catch (t: Throwable) {
                apk.delete()
                publish(JSONObject().put("success", false).put("state", "failed")
                    .put("code", "UPDATE_FAILED").put("message", t.message ?: t.javaClass.simpleName))
            }
        }
        return JSONObject().put("success", true).put("accepted", true).put("state", "downloading")
    }

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            require(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
            connection.inputStream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        } finally { connection.disconnect() }
    }

    private fun installWithPackageInstaller(apk: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(activity.packageName)
            if (Build.VERSION.SDK_INT >= 31) {
                // Android permits truly unattended installs only for trusted installers,
                // e.g. Device Owner / update owner. If not permitted, the platform
                // returns STATUS_PENDING_USER_ACTION and we keep the safe fallback.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input -> session.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); session.fsync(out) } }
            val intent = Intent(action).setPackage(activity.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(activity, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return runCatching { dpm.isDeviceOwnerApp(activity.packageName) }.getOrDefault(false)
    }

    private fun archivePackageName(apk: File): String? =
        activity.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

	private fun sameSigningCertificate(apk: File): Boolean {
		val pm = activity.packageManager
		val flags = PackageManager.GET_SIGNING_CERTIFICATES

		val installed = pm
			.getPackageInfo(activity.packageName, flags)
			.signingInfo
			?: return false

		val archive = pm
			.getPackageArchiveInfo(apk.absolutePath, flags)
			?.signingInfo
			?: return false

		fun certs(info: android.content.pm.SigningInfo): Set<String> =
			info.apkContentsSigners
				.map { sha256(it.toByteArray()) }
				.toSet()

		return certs(installed) == certs(archive)
	}

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n <= 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun currentVersionCode(): Long {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun publish(json: JSONObject) { state = JSONObject(json.toString()); RuntimeLog.add("app_update", json.toString()); activity.runOnUiThread { onStatus(JSONObject(json.toString())) } }
    private fun error(code: String, message: String) = JSONObject().put("success", false).put("code", code).put("message", message)

    fun dispose() { runCatching { activity.unregisterReceiver(receiver) }; executor.shutdownNow() }
}
