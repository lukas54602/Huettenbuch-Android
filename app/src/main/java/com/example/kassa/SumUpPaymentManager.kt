package com.example.kassa

import android.app.Activity
import org.json.JSONObject

/**
 * Go-live build without the private SumUp Tap-to-Pay SDK.
 *
 * External SumUp readers are controlled directly by Laravel through the SumUp API.
 * Internal NFC Tap-to-Pay stays behind the same Android bridge contract, but reports
 * itself as unavailable until a later APK is built with SumUp's private Maven SDK.
 */
class SumUpPaymentManager(
    @Suppress("UNUSED_PARAMETER") private val activity: Activity,
    @Suppress("UNUSED_PARAMETER") private val result: (String) -> Unit
) {
    fun initialize(@Suppress("UNUSED_PARAMETER") accessToken: String): JSONObject = unavailable()

    fun start(@Suppress("UNUSED_PARAMETER") request: JSONObject): JSONObject = unavailable()

    fun status(): JSONObject = JSONObject()
        .put("provider", "sumup_tap_to_pay")
        .put("available", false)
        .put("internalNfc", true)
        .put("initialized", false)
        .put("initializing", false)
        .put("active", false)
        .put("requestId", JSONObject.NULL)
        .put("interruptedRequestId", JSONObject.NULL)
        .put("recoveryRequired", false)
        .put("disabledReason", "SDK_NOT_INCLUDED")
        .put("lastError", "SumUp Tap-to-Pay SDK ist in diesem Build nicht enthalten")

    fun resetState() = Unit

    fun acknowledgeInterrupted(@Suppress("UNUSED_PARAMETER") request: String?): Boolean = true

    fun tearDown() = Unit

    private fun unavailable(): JSONObject = JSONObject()
        .put("success", false)
        .put("code", "SUMUP_TTP_NOT_AVAILABLE")
        .put("message", "SumUp Tap-to-Pay SDK ist in diesem Build nicht enthalten. Externe SumUp Reader werden direkt durch Laravel angesteuert.")
}
