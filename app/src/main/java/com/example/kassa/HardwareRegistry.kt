package com.example.kassa

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.view.Display

/**
 * Hardware discovery for the release build.
 *
 * Logging is deliberately compact:
 * - normal discovery does not create repetitive log entries
 * - a hardware snapshot is logged only when the topology changes
 * - NP521 details are logged only when the NP521 state changes
 */
class HardwareRegistry(private val context: Context) {

    fun discover(): List<HardwareDevice> {
        val displays = discoverDisplays()
        val devices = buildList {
            addAll(discoverUsbNfc())

            if (context.packageManager.hasSystemFeature("android.hardware.nfc")) {
                if (isSunmi()) {
                    val sunmiNfcApi = hasSunmiNfcApi()
                    add(HardwareDevice(
                        "android:nfc:front",
                        "SUNMI NFC Hauptdisplay",
                        HardwareType.NFC_READER,
                        available = sunmiNfcApi,
                        internal = true,
                        details = if (sunmiNfcApi) emptyMap() else mapOf("reason" to "SUNMI_NFC_API_UNAVAILABLE")
                    ))
                    if (hasPackage("com.sunmi.usbscreen") && hasSunmiCustomerDisplayWithSerial()) {
                        add(HardwareDevice(
                            "android:nfc:customer",
                            "SUNMI NFC Kundendisplay",
                            HardwareType.NFC_READER,
                            available = sunmiNfcApi,
                            internal = true,
                            details = if (sunmiNfcApi) emptyMap() else mapOf("reason" to "SUNMI_NFC_API_UNAVAILABLE")
                        ))
                    }
                } else {
                    add(HardwareDevice("android:nfc:internal", "${Build.MANUFACTURER} interner NFC-Reader", HardwareType.NFC_READER, internal = true))
                }
            }

            addAll(displays)

            if (isSunmi() && hasPackage("woyou.aidlservice.jiuiv5")) {
                add(HardwareDevice("sunmi:printer:internal", "SUNMI interner Bondrucker", HardwareType.PRINTER, internal = true))
            }

            if (context.packageManager.hasSystemFeature("android.hardware.nfc") && Build.VERSION.SDK_INT >= 30) {
                add(HardwareDevice("sumup:tap-to-pay", "SumUp Tap to Pay (interner NFC)", HardwareType.PAYMENT_PROVIDER,
                    available = false, internal = true, details = mapOf("reason" to "SDK_NOT_INCLUDED")))
            }
        }
        logHardwareSnapshotIfChanged(devices)
        return devices
    }

    private fun discoverUsbNfc(): List<HardwareDevice> {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager

        return usb.deviceList.values
            .filter { device ->
                (0 until device.interfaceCount).any { index ->
                    device.getInterface(index).interfaceClass == USB_CLASS_SMART_CARD
                }
            }
            .map { device ->
                val serial = if (usb.hasPermission(device)) {
                    runCatching { device.serialNumber }.getOrNull()
                } else {
                    null
                }

                val stableId = UsbHardwareIds.ccidReaderId(usb, device)
                val label = listOfNotNull(
                    runCatching { device.manufacturerName }.getOrNull(),
                    runCatching { device.productName }.getOrNull()
                ).joinToString(" ").ifBlank { "USB CCID Reader" }

                HardwareDevice(
                    stableId,
                    label,
                    HardwareType.NFC_READER,
                    details = mapOf(
                        "vendorId" to device.vendorId.toString(),
                        "productId" to device.productId.toString(),
                        "serial" to (serial ?: "")
                    )
                )
            }
    }

    private fun discoverDisplays(): List<HardwareDevice> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val presentationIds = dm
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .map { it.displayId }
            .toSet()

        val displays = linkedMapOf<Int, Display>()

        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
            .forEach { displays[it.displayId] = it }

        dm.displays
            .filter { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
            .forEach { displays[it.displayId] = it }

        return displays.values.map { display ->
            val logicalId = if (isSunmi() && displays.size == 1) {
                SUNMI_CUSTOMER_DISPLAY_ID
            } else {
                "display:${display.displayId}"
            }

            HardwareDevice(
                logicalId,
                display.name.ifBlank { "Kundendisplay ${display.displayId}" },
                HardwareType.CUSTOMER_DISPLAY,
                internal = isSunmi(),
                details = mapOf(
                    "androidDisplayId" to display.displayId.toString(),
                    "presentation" to presentationIds.contains(display.displayId).toString()
                )
            )
        }
    }

    /**
     * Logs one concise hardware line only when something actually changes.
     */
    private fun logHardwareSnapshotIfChanged(devices: List<HardwareDevice>) {
        val signature = devices
            .sortedBy { it.id }
            .joinToString("|") { "${it.type}:${it.id}:${it.available}" }

        if (signature == lastHardwareSignature) return
        lastHardwareSignature = signature

        RuntimeLog.add(
            "hardware_changed",
            if (signature.isBlank()) "none" else signature
        )
    }


    private fun hasSunmiNfcApi(): Boolean = runCatching {
        Class.forName("android.app.sunmi.SunmiCustomerManager")
        true
    }.getOrDefault(false)

    private fun hasSunmiCustomerDisplayWithSerial(): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.displays.any { display ->
            display.displayId != Display.DEFAULT_DISPLAY && display.isValid &&
                SUNMI_SERIAL_REGEX.containsMatchIn(display.name)
        }
    }

    private fun isSunmi(): Boolean =
        Build.MANUFACTURER.contains("SUNMI", true) ||
            Build.BRAND.contains("SUNMI", true)

    private fun hasPackage(name: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(name, 0)
            true
        }.getOrDefault(false)

    companion object {
        private const val USB_CLASS_SMART_CARD = 0x0B

        private const val SUNMI_CUSTOMER_DISPLAY_ID =
            "sunmi:display:customer"
        private val SUNMI_SERIAL_REGEX = Regex("[A-Z]{1,4}[0-9]{8,}")

        @Volatile
        private var lastHardwareSignature: String? = null

    }
}
