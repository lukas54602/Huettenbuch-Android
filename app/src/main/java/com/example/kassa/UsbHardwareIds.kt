package com.example.kassa
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
object UsbHardwareIds {
    fun ccidReaderId(usb: UsbManager, device: UsbDevice): String {
        val serial = if (usb.hasPermission(device)) runCatching { device.serialNumber }.getOrNull() else null
        val key = serial?.trim()?.takeIf { it.isNotEmpty() } ?: "${device.vendorId}:${device.productId}"
        return "usb:ccid:$key"
    }
}
