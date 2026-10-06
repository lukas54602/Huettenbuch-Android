package com.example.kassa

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Internal Android/SUNMI NFC adapter. No business logic; emits UID/presence only. */
class AndroidNfcService(
    private val activity: Activity,
    private val onUid: (uid: String, readerName: String) -> Unit,
    private val onRemoved: (readerName: String) -> Unit
) : NfcAdapter.ReaderCallback {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = false
    @Volatile private var lastUid: String? = null
    @Volatile private var lastSeenAt: Long = 0
    @Volatile private var cardPresent = false

    private val presenceCheck = object : Runnable {
        override fun run() {
            if (!enabled || !cardPresent) return
            val quietFor = SystemClock.elapsedRealtime() - lastSeenAt
            if (quietFor >= PRESENCE_TIMEOUT_MS) {
                cardPresent = false
                lastUid = null
                RuntimeLog.add("internal_nfc_removed")
                onRemoved(READER_NAME)
            } else {
                handler.postDelayed(this, PRESENCE_POLL_MS)
            }
        }
    }

    fun isAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true

    fun start(): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false
        if (enabled) return true
        a.enableReaderMode(
            activity,
            this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        enabled = true
        return true
    }

    fun stop() {
        if (!enabled) return
        runCatching { adapter?.disableReaderMode(activity) }
        enabled = false
        handler.removeCallbacks(presenceCheck)
        if (cardPresent) onRemoved(READER_NAME)
        cardPresent = false
        lastUid = null
    }

    /**
     * Re-arms Android ReaderMode without changing the selected SUNMI antenna.
     * Used only as a recovery path when the same SUNMI reader is already selected.
     */
    fun restartReaderMode(): Boolean {
        val a = adapter ?: return false
        if (!a.isEnabled) return false

        RuntimeLog.add("internal_nfc_recovery_start")

        runCatching {
            a.disableReaderMode(activity)
        }

        enabled = false
        handler.removeCallbacks(presenceCheck)

        if (cardPresent) {
            onRemoved(READER_NAME)
        }

        cardPresent = false
        lastUid = null

        val started = start()

        RuntimeLog.add(
            "internal_nfc_recovery_result",
            if (started) "OK" else "FAILED"
        )

        return started
    }

    override fun onTagDiscovered(tag: Tag) {
        val uid = tag.id?.joinToString("") { "%02X".format(it.toInt() and 0xFF) } ?: return
        val now = SystemClock.elapsedRealtime()
        lastSeenAt = now
        handler.removeCallbacks(presenceCheck)
        handler.postDelayed(presenceCheck, PRESENCE_POLL_MS)

        // A tag held on the antenna must produce one logical presentation only.
        // Reader-mode rediscovery refreshes lastSeenAt; after a quiet period we emit removed.
        if (cardPresent && uid == lastUid) return

        cardPresent = true
        lastUid = uid
        RuntimeLog.add("internal_nfc_presented", uid)
        onUid(uid, READER_NAME)
    }

    companion object {
        private const val READER_NAME = "Interner NFC-Reader"
        private const val PRESENCE_TIMEOUT_MS = 2500L
        private const val PRESENCE_POLL_MS = 500L
    }
}
