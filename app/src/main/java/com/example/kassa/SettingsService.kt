package com.example.kassa

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SettingsService(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
        terminalName = prefs.getString(KEY_TERMINAL_NAME, DEFAULT_TERMINAL_NAME) ?: DEFAULT_TERMINAL_NAME,
        setupComplete = prefs.getBoolean(KEY_SETUP_COMPLETE, false)
    )

    fun saveConfiguration(serverUrl: String, terminalName: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, normalizeServerUrl(serverUrl))
            .putString(KEY_TERMINAL_NAME, terminalName.trim())
            .apply()
    }

    fun completeSetup(serverUrl: String, terminalName: String, adminPin: String) {
        saveAdminPin(adminPin)
        prefs.edit()
            .putString(KEY_SERVER_URL, normalizeServerUrl(serverUrl))
            .putString(KEY_TERMINAL_NAME, terminalName.trim())
            .putBoolean(KEY_SETUP_COMPLETE, true)
            .apply()
    }

    fun verifyAdminPin(pin: String): Boolean {
        val saltBase64 = prefs.getString(KEY_ADMIN_PIN_SALT, null) ?: return false
        val hashBase64 = prefs.getString(KEY_ADMIN_PIN_HASH, null) ?: return false

        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val expected = Base64.decode(hashBase64, Base64.NO_WRAP)
        val actual = derivePinHash(pin, salt)

        return MessageDigest.isEqual(expected, actual)
    }


    fun changeAdminPin(newPin: String) {
        saveAdminPin(newPin)
    }

    private fun saveAdminPin(pin: String) {
        require(pin.length >= 4) { "Admin-PIN muss mindestens 4 Zeichen lang sein." }

        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derivePinHash(pin, salt)

        prefs.edit()
            .putString(KEY_ADMIN_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_ADMIN_PIN_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    private fun derivePinHash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun normalizeServerUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        return if (trimmed.isBlank()) DEFAULT_SERVER_URL else trimmed
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://huettenbuch.ych.at"
        const val DEFAULT_TERMINAL_NAME = "Kassa 1"

        private const val PREFS = "huettenbuch_settings"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TERMINAL_NAME = "terminal_name"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_ADMIN_PIN_SALT = "admin_pin_salt"
        private const val KEY_ADMIN_PIN_HASH = "admin_pin_hash"
    }
}
