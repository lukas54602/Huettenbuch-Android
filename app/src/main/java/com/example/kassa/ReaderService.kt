package com.example.kassa

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReaderService(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onReaderStatusChanged(status: NfcReaderStatus, readerName: String?)
        fun onCardPresented(uid: String, readerName: String)
        fun onCardRemoved(readerName: String)
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val running = AtomicBoolean(false)

    @Volatile
    var status: NfcReaderStatus = NfcReaderStatus.DISCONNECTED
        private set

    @Volatile
    var readerName: String? = null
        private set

    private var scanTask: ScheduledFuture<*>? = null
    private var pollTask: ScheduledFuture<*>? = null
    private var device: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var connection: UsbDeviceConnection? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var sequence = 0
    private var cardHandled = false
    private var permissionRequestedForDeviceId: Int? = null
    private var permissionDeniedForDeviceId: Int? = null
    @Volatile private var preferredReaderId: String? = null

    fun setPreferredReaderId(id: String?) { preferredReaderId = id; restart() }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            when (safeIntent.action) {
                ACTION_USB_PERMISSION -> {
                    val permissionDevice = extractUsbDevice(safeIntent) ?: return
                    permissionRequestedForDeviceId = null

                    val granted = safeIntent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED,
                        false
                    ) && usbManager.hasPermission(permissionDevice)

                    if (granted) {
                        permissionDeniedForDeviceId = null
                        worker.execute { openDevice(permissionDevice) }
                    } else {
                        permissionDeniedForDeviceId = permissionDevice.deviceId
                        updateStatus(NfcReaderStatus.ERROR, deviceLabel(permissionDevice))
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    worker.execute { scanForReader() }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val detached = extractUsbDevice(safeIntent)
                    if (detached != null) {
                        if (permissionDeniedForDeviceId == detached.deviceId) {
                            permissionDeniedForDeviceId = null
                        }
                        if (detached.deviceId == device?.deviceId) {
                            worker.execute {
                                closeCurrentReader(notifyDisconnected = true)
                                scanForReader()
                            }
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        ContextCompat.registerReceiver(
            appContext,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        updateStatus(NfcReaderStatus.CONNECTING, null)
        scanTask = worker.scheduleWithFixedDelay(
            { scanForReader() },
            0,
            5,
            TimeUnit.SECONDS
        )
    }

    fun restart() {
        worker.execute {
            closeCurrentReader(notifyDisconnected = true)
            permissionRequestedForDeviceId = null
            permissionDeniedForDeviceId = null
            if (!running.get()) running.set(true)
            scanForReader()
        }
    }

    fun stop() {
        running.set(false)
        scanTask?.cancel(true)
        scanTask = null
        pollTask?.cancel(true)
        pollTask = null
        worker.execute {
            closeCurrentReader(notifyDisconnected = true)
            updateStatus(NfcReaderStatus.DISCONNECTED, null)
        }
    }

    fun dispose() {
        running.set(false)
        scanTask?.cancel(true)
        pollTask?.cancel(true)
        runCatching { appContext.unregisterReceiver(usbReceiver) }
        closeCurrentReader(notifyDisconnected = false)
        worker.shutdownNow()
    }

    private fun scanForReader() {
        if (!running.get()) return
        if (connection != null && device != null) return

        val candidates = usbManager.deviceList.values.filter { usbDevice -> findCcidInterface(usbDevice) != null }
        val candidate = candidates.firstOrNull { preferredReaderId == null || stableReaderId(it) == preferredReaderId }

        if (candidate == null) {
            updateStatus(NfcReaderStatus.NO_READER, null)
            return
        }

        val name = deviceLabel(candidate)

        if (permissionDeniedForDeviceId == candidate.deviceId) {
            updateStatus(NfcReaderStatus.ERROR, name)
            return
        }

        updateStatus(NfcReaderStatus.CONNECTING, name)

        if (!usbManager.hasPermission(candidate)) {
            requestPermission(candidate)
            return
        }

        openDevice(candidate)
    }

    private fun requestPermission(candidate: UsbDevice) {
        if (permissionRequestedForDeviceId == candidate.deviceId) return
        permissionRequestedForDeviceId = candidate.deviceId

        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            candidate.deviceId,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        usbManager.requestPermission(candidate, permissionIntent)
    }

    private fun openDevice(candidate: UsbDevice) {
        if (!running.get()) return
        if (connection != null && device?.deviceId == candidate.deviceId) return

        closeCurrentReader(notifyDisconnected = false)

        val ccidInterface = findCcidInterface(candidate)
        if (ccidInterface == null) {
            updateStatus(NfcReaderStatus.NO_READER, null)
            return
        }

        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null

        for (index in 0 until ccidInterface.endpointCount) {
            val endpoint = ccidInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue

            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> foundIn = endpoint
                UsbConstants.USB_DIR_OUT -> foundOut = endpoint
            }
        }

        if (foundIn == null || foundOut == null) {
            updateStatus(NfcReaderStatus.ERROR, deviceLabel(candidate))
            return
        }

        val opened = usbManager.openDevice(candidate)
        if (opened == null || !opened.claimInterface(ccidInterface, true)) {
            opened?.close()
            updateStatus(NfcReaderStatus.ERROR, deviceLabel(candidate))
            return
        }

        device = candidate
        usbInterface = ccidInterface
        connection = opened
        bulkIn = foundIn
        bulkOut = foundOut
        readerName = deviceLabel(candidate)
        cardHandled = false
        sequence = 0

        updateStatus(NfcReaderStatus.CONNECTED, readerName)
        startPolling()
    }

    private fun startPolling() {
        pollTask?.cancel(true)
        pollTask = worker.scheduleWithFixedDelay(
            { pollCardState() },
            0,
            700,
            TimeUnit.MILLISECONDS
        )
    }

    private fun pollCardState() {
        if (!running.get()) return
        val currentName = readerName ?: return

        try {
            val slotStatus = sendCcidCommand(
                messageType = PC_TO_RDR_GET_SLOT_STATUS,
                payload = ByteArray(0),
                parameter0 = 0,
                parameter1 = 0,
                parameter2 = 0
            ) ?: throw IllegalStateException("Keine CCID-Antwort")

            if (slotStatus.messageType != RDR_TO_PC_SLOT_STATUS) {
                throw IllegalStateException("Unerwartete CCID-Antwort")
            }

            val iccStatus = slotStatus.status and 0x03

            when (iccStatus) {
                ICC_PRESENT_ACTIVE, ICC_PRESENT_INACTIVE -> {
                    updateStatus(NfcReaderStatus.CARD_PRESENT, currentName)

                    if (!cardHandled) {
                        if (iccStatus == ICC_PRESENT_INACTIVE) {
                            powerOnCard()
                            Thread.sleep(80)
                        }

                        val uid = readUid()
                        if (!uid.isNullOrBlank()) {
                            cardHandled = true
                            listener.onCardPresented(uid, currentName)
                        }
                    }
                }

                ICC_NOT_PRESENT -> {
                    if (cardHandled) {
                        cardHandled = false
                        listener.onCardRemoved(currentName)
                    }
                    updateStatus(NfcReaderStatus.WAITING_FOR_CARD, currentName)
                }

                else -> updateStatus(NfcReaderStatus.WAITING_FOR_CARD, currentName)
            }
        } catch (_: Exception) {
            closeCurrentReader(notifyDisconnected = true)
            updateStatus(NfcReaderStatus.ERROR, currentName)
        }
    }

    private fun powerOnCard() {
        val response = sendCcidCommand(
            messageType = PC_TO_RDR_ICC_POWER_ON,
            payload = ByteArray(0),
            parameter0 = 0,
            parameter1 = 0,
            parameter2 = 0
        ) ?: throw IllegalStateException("Karte konnte nicht aktiviert werden")

        if (response.messageType != RDR_TO_PC_DATA_BLOCK) {
            throw IllegalStateException("Ungültige PowerOn-Antwort")
        }
    }

    private fun readUid(): String? {
        val apdu = byteArrayOf(
            0xFF.toByte(),
            0xCA.toByte(),
            0x00,
            0x00,
            0x00
        )

        val response = sendCcidCommand(
            messageType = PC_TO_RDR_XFR_BLOCK,
            payload = apdu,
            parameter0 = 0,
            parameter1 = 0,
            parameter2 = 0
        ) ?: return null

        if (response.messageType != RDR_TO_PC_DATA_BLOCK) return null
        if (response.payload.size < 3) return null

        val sw1 = response.payload[response.payload.size - 2].toInt() and 0xFF
        val sw2 = response.payload[response.payload.size - 1].toInt() and 0xFF
        if (sw1 != 0x90 || sw2 != 0x00) return null

        val uidBytes = response.payload.copyOfRange(0, response.payload.size - 2)
        if (uidBytes.isEmpty()) return null

        return uidBytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    @Synchronized
    private fun sendCcidCommand(
        messageType: Int,
        payload: ByteArray,
        parameter0: Int,
        parameter1: Int,
        parameter2: Int
    ): CcidResponse? {
        val currentConnection = connection ?: return null
        val outEndpoint = bulkOut ?: return null
        val inEndpoint = bulkIn ?: return null

        val seq = sequence and 0xFF
        sequence = (sequence + 1) and 0xFF

        val command = ByteArray(CCID_HEADER_SIZE + payload.size)
        command[0] = messageType.toByte()
        writeLittleEndianInt(command, 1, payload.size)
        command[5] = 0
        command[6] = seq.toByte()
        command[7] = parameter0.toByte()
        command[8] = parameter1.toByte()
        command[9] = parameter2.toByte()
        payload.copyInto(command, CCID_HEADER_SIZE)

        val sent = currentConnection.bulkTransfer(
            outEndpoint,
            command,
            command.size,
            USB_TIMEOUT_MS
        )
        if (sent != command.size) return null

        val buffer = ByteArray(512)
        val received = currentConnection.bulkTransfer(
            inEndpoint,
            buffer,
            buffer.size,
            USB_TIMEOUT_MS
        )
        if (received < CCID_HEADER_SIZE) return null

        val responseSequence = buffer[6].toInt() and 0xFF
        if (responseSequence != seq) return null

        val payloadLength = readLittleEndianInt(buffer, 1)
        if (payloadLength < 0 || CCID_HEADER_SIZE + payloadLength > received) return null

        val responsePayload = buffer.copyOfRange(
            CCID_HEADER_SIZE,
            CCID_HEADER_SIZE + payloadLength
        )

        return CcidResponse(
            messageType = buffer[0].toInt() and 0xFF,
            status = buffer[7].toInt() and 0xFF,
            error = buffer[8].toInt() and 0xFF,
            payload = responsePayload
        )
    }

    private fun closeCurrentReader(notifyDisconnected: Boolean) {
        pollTask?.cancel(true)
        pollTask = null

        val oldName = readerName
        val currentConnection = connection
        val currentInterface = usbInterface

        if (currentConnection != null && currentInterface != null) {
            runCatching { currentConnection.releaseInterface(currentInterface) }
        }
        runCatching { currentConnection?.close() }

        device = null
        usbInterface = null
        connection = null
        bulkIn = null
        bulkOut = null
        readerName = null
        cardHandled = false

        if (notifyDisconnected && oldName != null) {
            listener.onReaderStatusChanged(NfcReaderStatus.DISCONNECTED, oldName)
        }
    }

    private fun updateStatus(newStatus: NfcReaderStatus, name: String?) {
        val changed = status != newStatus || readerName != name
        status = newStatus
        if (name != null) readerName = name

        if (changed) {
            listener.onReaderStatusChanged(newStatus, readerName)
        }
    }

    private fun findCcidInterface(usbDevice: UsbDevice): UsbInterface? {
        for (index in 0 until usbDevice.interfaceCount) {
            val candidate = usbDevice.getInterface(index)
            if (candidate.interfaceClass == USB_CLASS_CCID) return candidate
        }
        return null
    }

    fun availableReaders(): List<HardwareDevice> = usbManager.deviceList.values
        .filter { findCcidInterface(it) != null }
        .map { HardwareDevice(stableReaderId(it), deviceLabel(it), HardwareType.NFC_READER) }

    private fun stableReaderId(usbDevice: UsbDevice): String {
        return UsbHardwareIds.ccidReaderId(usbManager, usbDevice)
    }

    private fun deviceLabel(usbDevice: UsbDevice): String {
        val product = runCatching { usbDevice.productName }.getOrNull()
        val manufacturer = runCatching { usbDevice.manufacturerName }.getOrNull()

        return listOfNotNull(manufacturer, product)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { usbDevice.deviceName }
    }

    @Suppress("DEPRECATION")
    private fun extractUsbDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun writeLittleEndianInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readLittleEndianInt(source: ByteArray, offset: Int): Int {
        return (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)
    }

    private data class CcidResponse(
        val messageType: Int,
        val status: Int,
        val error: Int,
        val payload: ByteArray
    )

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.kassa.USB_PERMISSION"
        private const val USB_CLASS_CCID = 0x0B
        private const val CCID_HEADER_SIZE = 10
        private const val USB_TIMEOUT_MS = 3_000

        private const val PC_TO_RDR_ICC_POWER_ON = 0x62
        private const val PC_TO_RDR_GET_SLOT_STATUS = 0x65
        private const val PC_TO_RDR_XFR_BLOCK = 0x6F

        private const val RDR_TO_PC_DATA_BLOCK = 0x80
        private const val RDR_TO_PC_SLOT_STATUS = 0x81

        private const val ICC_PRESENT_ACTIVE = 0
        private const val ICC_PRESENT_INACTIVE = 1
        private const val ICC_NOT_PRESENT = 2
    }
}
