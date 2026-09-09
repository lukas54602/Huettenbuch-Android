package com.example.kassa

import org.json.JSONArray
import org.json.JSONObject

enum class HardwareType { NFC_READER, PAYMENT_PROVIDER, PAYMENT_TERMINAL, PRINTER, CUSTOMER_DISPLAY }

data class HardwareDevice(
    val id: String,
    val name: String,
    val type: HardwareType,
    val available: Boolean = true,
    val internal: Boolean = false,
    val details: Map<String, String> = emptyMap()
) {
    fun toJson() = JSONObject()
        .put("id", id).put("name", name).put("type", type.name.lowercase())
        .put("available", available).put("internal", internal)
        .put("details", JSONObject(details))
}

data class HardwareConfig(
    val nfcReaderId: String? = null,
    val paymentProviderId: String? = null,
    val paymentTerminalId: String? = null,
    val printerId: String? = null,
    val customerDisplayId: String? = null,
    val version: Long = 0
) {
    fun toJson() = JSONObject()
        .put("nfcReaderId", nfcReaderId).put("paymentProviderId", paymentProviderId)
        .put("paymentTerminalId", paymentTerminalId).put("printerId", printerId)
        .put("customerDisplayId", customerDisplayId).put("version", version)

    companion object {
        fun fromJson(json: JSONObject) = HardwareConfig(
            json.optString("nfcReaderId").takeIf { it.isNotBlank() },
            json.optString("paymentProviderId").takeIf { it.isNotBlank() },
            json.optString("paymentTerminalId").takeIf { it.isNotBlank() },
            json.optString("printerId").takeIf { it.isNotBlank() },
            json.optString("customerDisplayId").takeIf { it.isNotBlank() },
            json.optLong("version", System.currentTimeMillis())
        )
    }
}

fun List<HardwareDevice>.toJsonArray(): JSONArray = JSONArray().also { a -> forEach { a.put(it.toJson()) } }
