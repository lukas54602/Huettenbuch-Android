package com.example.kassa

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RuntimeLog {
    private const val MAX_ENTRIES = 250
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    private data class Entry(val createdAt: Long, val json: JSONObject)
    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(event: String, details: String = "") {
        val now = System.currentTimeMillis()
        prune(now)

        entries.addLast(
            Entry(
                now,
                JSONObject()
                    .put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(now)))
                    .put("event", event)
                    .put("details", details)
            )
        )

        while (entries.size > MAX_ENTRIES) entries.removeFirst()
    }

    @Synchronized
    fun json(): String {
        prune(System.currentTimeMillis())
        return JSONArray(entries.map { it.json }).toString()
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun addHardware(event: String, deviceId: String?, result: String = "", details: String = "") =
        add(
            "hw_$event",
            listOfNotNull(
                deviceId?.let { "id=$it" },
                result.takeIf { it.isNotBlank() }?.let { "result=$it" },
                details.takeIf { it.isNotBlank() }
            ).joinToString("; ")
        )

    private fun prune(now: Long) {
        val cutoff = now - MAX_AGE_MS
        while (entries.isNotEmpty() && entries.first().createdAt < cutoff) {
            entries.removeFirst()
        }
    }
}
