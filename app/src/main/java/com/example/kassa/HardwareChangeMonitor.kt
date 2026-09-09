package com.example.kassa
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
class HardwareChangeMonitor(context: Context, private val changed: () -> Unit) : DisplayManager.DisplayListener {
    private val app = context.applicationContext
    private val dm = app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val receiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { changed() } }
    init {
        ContextCompat.registerReceiver(app, receiver, IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }, ContextCompat.RECEIVER_EXPORTED)
        dm.registerDisplayListener(this, null)
    }
    override fun onDisplayAdded(displayId: Int) = changed()
    override fun onDisplayRemoved(displayId: Int) = changed()
    override fun onDisplayChanged(displayId: Int) = changed()
    fun dispose() { runCatching { app.unregisterReceiver(receiver) }; runCatching { dm.unregisterDisplayListener(this) } }
}
