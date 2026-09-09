package com.example.kassa

import android.content.Context
import android.content.Intent
import android.os.Build

/** SUNMI customer-display power switch via the exported USB Screen control API. */
class SunmiDisplayController(context: Context) {
    private val appContext = context.applicationContext

    fun setEnabled(enabled: Boolean, serial: String = "") {
        if (!isSupported()) return
        val intent = Intent(ACTION_SET_CONTROL).apply {
            setPackage(SUNMI_USB_SCREEN_PACKAGE)
            putExtra("sn", serial)
            putExtra("type", TYPE_OPTION)
            putExtra("key", KEY_SCREEN_SW)
            putExtra("value", if (enabled) 1 else 0)
        }
        appContext.sendBroadcast(intent)
        RuntimeLog.addHardware("sunmi_display_power", SUNMI_DISPLAY_ID, "REQUESTED", "enabled=$enabled")
    }

    private fun isSupported(): Boolean = isSunmi() && runCatching {
        appContext.packageManager.getPackageInfo(SUNMI_USB_SCREEN_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private fun isSunmi(): Boolean =
        Build.MANUFACTURER.contains("SUNMI", true) || Build.BRAND.contains("SUNMI", true)

    companion object {
        private const val SUNMI_USB_SCREEN_PACKAGE = "com.sunmi.usbscreen"
        private const val ACTION_SET_CONTROL = "com.sunmi.usbscreen.ACTION_SET_CONTROL"
        private const val TYPE_OPTION = 1
        private const val KEY_SCREEN_SW = 2
        private const val SUNMI_DISPLAY_ID = "sunmi:display:customer"
    }
}
