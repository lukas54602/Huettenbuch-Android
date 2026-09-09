package com.example.kassa

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.SunmiPrinterService
import org.json.JSONObject

/** Thin SUNMI printer adapter. Receipt content/layout is supplied by Laravel. */
class SunmiPrinterManager(context: Context) {
    @Volatile private var service: SunmiPrinterService? = null
    private val appContext = context.applicationContext

    private val callback = object : InnerPrinterCallback() {
        override fun onConnected(printerService: SunmiPrinterService) { service = printerService; RuntimeLog.add("printer_connected") }
        override fun onDisconnected() { service = null; RuntimeLog.add("printer_disconnected") }
    }

    init {
        if (isSunmi()) {
            RuntimeLog.addHardware("printer_bind_requested", "sunmi:printer:internal")
            runCatching { InnerPrinterManager.getInstance().bindService(appContext, callback) }
                .onFailure { RuntimeLog.addHardware("printer_bind", "sunmi:printer:internal", "FAILED", it.message ?: it.javaClass.simpleName) }
        }
    }

    fun isReady(): Boolean = service != null

    fun status(): JSONObject {
        val printer = service
        val state = if (printer == null) null else runCatching { printer.updatePrinterState() }.getOrNull()
        return JSONObject()
            .put("ready", printer != null && (state == null || state == 1))
            .put("serviceConnected", printer != null)
            .put("state", state)
            .put("stateText", stateText(state))
            .put("printerId", "sunmi:printer:internal")
    }

    fun print(deviceId: String, payload: JSONObject): JSONObject {
        if (deviceId != "sunmi:printer:internal") {
            return error("PRINTER_UNSUPPORTED", deviceId)
        }

        val printer = service ?: return error("PRINTER_UNAVAILABLE", deviceId)
        val before = runCatching { printer.updatePrinterState() }.getOrNull()

        if (before != null && before != 1) {
            return error("PRINTER_NOT_READY", stateText(before))
        }

        return runCatching {
            printer.printerInit(null)

            val lines = payload.optJSONArray("lines")

            if (lines != null && lines.length() > 0) {
                for (i in 0 until lines.length()) {
                    val line = lines.optJSONObject(i) ?: continue

                    when (line.optString("type")) {
                        "text" -> {
                            val align = when (line.optString("align")) {
                                "center" -> 1
                                "right" -> 2
                                else -> 0
                            }
                            printer.setAlignment(align, null)

                            val bold = line.optBoolean("bold", false)
                            val size = line.optInt("size", DEFAULT_TEXT_SIZE).coerceIn(16, 64)

                            runCatching { printer.setFontSize(size.toFloat(), null) }
                            runCatching {
                                printer.sendRAWData(
                                    byteArrayOf(0x1B, 0x45, if (bold) 0x01 else 0x00),
                                    null
                                )
                            }

                            printer.printText(line.optString("text") + "\n", null)

                            if (bold) {
                                runCatching {
                                    printer.sendRAWData(byteArrayOf(0x1B, 0x45, 0x00), null)
                                }
                            }
                            runCatching { printer.setFontSize(DEFAULT_TEXT_SIZE.toFloat(), null) }
                        }

                        "image" -> {
                            val encoded = line.optString("base64")
                                .substringAfter("base64,", line.optString("base64"))
                            if (encoded.isNotBlank()) {
                                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    printer.setAlignment(
                                        if (line.optString("align") == "center") 1 else 0,
                                        null
                                    )
                                    printer.printBitmap(scaleForReceipt(bitmap), null)
                                }
                            }
                        }

                        "feed" -> printer.lineWrap(
                            line.optInt("lines", 1).coerceIn(0, 20),
                            null
                        )

                        "cut" -> runCatching { printer.cutPaper(null) }
                    }
                }
            } else {
                // Hardwaretest / Abwärtskompatibilität.
                val text = payload.optString("text")
                val qr = payload.optString("qrCode")
                val imageBase64 = payload.optString("imageBase64")
                    .substringAfter("base64,", payload.optString("imageBase64"))

                if (text.isBlank() && qr.isBlank() && imageBase64.isBlank()) {
                    return error("PRINT_PAYLOAD_EMPTY", "lines/text/qrCode/imageBase64")
                }

                if (imageBase64.isNotBlank()) {
                    val bytes = Base64.decode(imageBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return error("PRINT_IMAGE_INVALID", "imageBase64")
                    printer.printBitmap(scaleForReceipt(bitmap), null)
                }

                if (text.isNotBlank()) printer.printText(text, null)

                if (qr.isNotBlank()) {
                    printer.printQRCode(
                        qr,
                        payload.optInt("qrModuleSize", 6).coerceIn(1, 16),
                        payload.optInt("qrErrorLevel", 1).coerceIn(0, 3),
                        null
                    )
                }

                val feedLines = payload.optInt("feedLines", 3).coerceIn(0, 20)
                if (feedLines > 0) printer.lineWrap(feedLines, null)
                if (payload.optBoolean("cut", true)) runCatching { printer.cutPaper(null) }
            }

            printer.setAlignment(0, null)

            val after = runCatching { printer.updatePrinterState() }.getOrNull()
            RuntimeLog.addHardware(
                "printer_print",
                deviceId,
                if (after == null || after == 1) "OK" else "FAILED",
                "after=${stateText(after)}"
            )

            JSONObject()
                .put("success", after == null || after == 1)
                .put("submitted", true)
                .put("printerId", deviceId)
                .put("state", after)
                .put("stateText", stateText(after))
        }.getOrElse {
            RuntimeLog.addHardware(
                "printer_print",
                deviceId,
                "FAILED",
                it.message ?: it.javaClass.simpleName
            )
            error("PRINT_FAILED", it.message ?: it.javaClass.simpleName)
        }
    }

    private fun scaleForReceipt(bitmap: Bitmap): Bitmap {
        val maxWidth = RECEIPT_IMAGE_MAX_WIDTH_PX
        if (bitmap.width <= maxWidth) return bitmap

        val ratio = maxWidth.toFloat() / bitmap.width.toFloat()
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(
            bitmap,
            maxWidth,
            targetHeight,
            true
        )
    }

    fun dispose() {
        if (isSunmi()) runCatching { InnerPrinterManager.getInstance().unBindService(appContext, callback) }
    }

    private fun isSunmi(): Boolean =
        Build.MANUFACTURER.contains("SUNMI", true) || Build.BRAND.contains("SUNMI", true)

    private fun stateText(state: Int?): String = when (state) {
        null -> "UNKNOWN"
        1 -> "READY"
        2 -> "PREPARING"
        3 -> "COMMUNICATION_ERROR"
        4 -> "OUT_OF_PAPER"
        5 -> "OVERHEATED"
        6 -> "COVER_OPEN"
        7 -> "CUTTER_ERROR"
        8 -> "CUTTER_RECOVERED"
        9 -> "BLACK_MARK_NOT_FOUND"
        505 -> "PRINTER_NOT_DETECTED"
        507 -> "FIRMWARE_UPDATE_FAILED"
        else -> "STATE_$state"
    }

    private fun error(code: String, message: String) = JSONObject()
        .put("success", false).put("code", code).put("message", message)
    companion object {
        // Conservative width for SUNMI 80-mm thermal printers.
        private const val RECEIPT_IMAGE_MAX_WIDTH_PX = 384
        private const val DEFAULT_TEXT_SIZE = 24
    }
}
