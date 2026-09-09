package com.example.kassa

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class ApiService(
    private val identity: TerminalIdentityService
) {
    enum class ServerState {
        ONLINE,
        MAINTENANCE,
        OFFLINE
    }

    data class ApiResult<T>(
        val statusCode: Int,
        val value: T? = null,
        val rawBody: String = "",
        val transportError: Boolean = false
    ) {
        val isSuccessful: Boolean
            get() = statusCode in 200..299 && !transportError
    }

    fun testConnection(settings: AppSettings): ServerState {
        val result = request(
            method = "GET",
            settings = settings,
            path = "/api/ping",
            body = null,
            signed = false
        )

        return when {
            result.transportError -> ServerState.OFFLINE
            result.statusCode == HttpURLConnection.HTTP_UNAVAILABLE -> ServerState.MAINTENANCE
            result.statusCode in 200..299 -> ServerState.ONLINE
            else -> ServerState.OFFLINE
        }
    }

    fun registerTerminal(settings: AppSettings): ApiResult<Boolean> {
        val payload = JSONObject()
            .put("uuid", identity.terminalUuid)
            .put("name", settings.terminalName)
            .put("public_key", identity.getPublicKeyPem())

        val result = request(
            method = "POST",
            settings = settings,
            path = "/api/terminal/register",
            body = payload.toString(),
            signed = false
        )

        return ApiResult(
            statusCode = result.statusCode,
            value = result.isSuccessful,
            rawBody = result.rawBody,
            transportError = result.transportError
        )
    }

    fun getRegistrationStatus(settings: AppSettings): ApiResult<String> {
        val result = request(
            method = "POST",
            settings = settings,
            path = "/api/terminal/registration-status",
            body = JSONObject().toString(),
            signed = true
        )

        val status = if (result.isSuccessful) {
            runCatching { JSONObject(result.rawBody).optString("status") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
        } else {
            null
        }

        return ApiResult(
            statusCode = result.statusCode,
            value = status,
            rawBody = result.rawBody,
            transportError = result.transportError
        )
    }

    fun createWebSessionUrl(settings: AppSettings): ApiResult<String> {
        val result = request(
            method = "POST",
            settings = settings,
            path = "/api/terminal/web-session",
            body = JSONObject().toString(),
            signed = true
        )

        val url = if (result.isSuccessful) {
            runCatching {
                val json = JSONObject(result.rawBody)
                if (json.optBoolean("success")) json.optString("url") else ""
            }.getOrDefault("").takeIf { it.isNotBlank() }
        } else {
            null
        }

        return ApiResult(
            statusCode = result.statusCode,
            value = url,
            rawBody = result.rawBody,
            transportError = result.transportError
        )
    }

    fun sendUid(
        settings: AppSettings,
        uid: String,
        reader: String
    ): ApiResult<Boolean> {
        val payload = JSONObject()
            .put("uid", uid)
            .put("terminal", identity.terminalUuid)
            .put("reader", reader)

        val result = request(
            method = "POST",
            settings = settings,
            path = "/api/nfc/read",
            body = payload.toString(),
            signed = true
        )

        val success = if (result.isSuccessful) {
            runCatching { JSONObject(result.rawBody).optBoolean("success", false) }
                .getOrDefault(false)
        } else {
            false
        }

        return ApiResult(
            statusCode = result.statusCode,
            value = success,
            rawBody = result.rawBody,
            transportError = result.transportError
        )
    }

    fun sendHeartbeat(
        settings: AppSettings,
        readerName: String,
        readerStatus: NfcReaderStatus,
        appVersion: String,
        appVersionCode: Long
    ): ApiResult<Boolean> {
        val readerConnected = readerStatus == NfcReaderStatus.CONNECTED ||
            readerStatus == NfcReaderStatus.WAITING_FOR_CARD ||
            readerStatus == NfcReaderStatus.CARD_PRESENT

        val payload = JSONObject()
            .put("terminal_id", identity.terminalUuid)
            .put("terminal_name", settings.terminalName)
            .put("reader_connected", readerConnected)
            .put("reader_name", readerName)
            .put("app_version", appVersion)
            .put("app_version_code", appVersionCode)

        val result = request(
            method = "POST",
            settings = settings,
            path = "/api/terminal/heartbeat",
            body = payload.toString(),
            signed = true
        )

        return ApiResult(
            statusCode = result.statusCode,
            value = result.isSuccessful,
            rawBody = result.rawBody,
            transportError = result.transportError
        )
    }

    private fun request(
        method: String,
        settings: AppSettings,
        path: String,
        body: String?,
        signed: Boolean
    ): ApiResult<Unit> {
        var connection: HttpURLConnection? = null

        return try {
            val url = URL(settings.serverUrl.trimEnd('/') + path)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            }

            if (signed) {
                val jsonBody = body ?: "{}"
                applySignatureHeaders(connection, method, path, jsonBody)
            }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val responseBody = readBody(
                if (code in 200..399) connection.inputStream else connection.errorStream
            )

            ApiResult(statusCode = code, rawBody = responseBody)
        } catch (_: Exception) {
            ApiResult(statusCode = -1, transportError = true)
        } finally {
            connection?.disconnect()
        }
    }

    private fun applySignatureHeaders(
        connection: HttpURLConnection,
        method: String,
        path: String,
        body: String
    ) {
        val bodyHash = sha256Hex(body)
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "")

        val signaturePayload = listOf(
            method.uppercase(Locale.ROOT),
            path,
            timestamp,
            nonce,
            bodyHash
        ).joinToString("\n")

        connection.setRequestProperty("X-Terminal-Id", identity.terminalUuid)
        connection.setRequestProperty("X-Timestamp", timestamp)
        connection.setRequestProperty("X-Nonce", nonce)
        connection.setRequestProperty("X-Signature", identity.sign(signaturePayload))
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readBody(input: InputStream?): String {
        if (input == null) return ""

        return BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            buildString {
                var first = true
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!first) append('\n')
                    append(line)
                    first = false
                }
            }
        }
    }
}
