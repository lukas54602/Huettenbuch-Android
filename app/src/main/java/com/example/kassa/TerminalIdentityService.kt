package com.example.kassa

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

class TerminalIdentityService(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    val terminalUuid: String = loadOrCreateUuid()

    init {
        ensureKeyPair()
    }

    fun getPublicKeyPem(): String {
        val certificate = keyStore.getCertificate(KEY_ALIAS)
            ?: error("Terminal public key not found")
        val encoded = certificate.publicKey.encoded
        val body = Base64.encodeToString(encoded, Base64.NO_WRAP)
            .chunked(64)
            .joinToString("\n")

        return "-----BEGIN PUBLIC KEY-----\n$body\n-----END PUBLIC KEY-----\n"
    }

    fun sign(payload: String): String {
        val privateKey = keyStore.getKey(KEY_ALIAS, null)
            ?: error("Terminal private key not found")

        val signature = Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey as java.security.PrivateKey)
        signature.update(payload.toByteArray(Charsets.UTF_8))

        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    private fun loadOrCreateUuid(): String {
        val existing = prefs.getString(KEY_UUID, null)?.trim()
        if (!existing.isNullOrBlank()) return existing

        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_UUID, created).commit()
        return created
    }

    private fun ensureKeyPair() {
        if (keyStore.containsAlias(KEY_ALIAS)) return

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEY_STORE
        )

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(3072)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .build()

        generator.initialize(spec)
        generator.generateKeyPair()
    }

    companion object {
        private const val PREFS = "terminal_identity"
        private const val KEY_UUID = "terminal_uuid"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "huettenbuch_terminal_rsa"
    }
}
