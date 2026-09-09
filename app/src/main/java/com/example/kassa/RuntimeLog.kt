package com.example.kassa
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
object RuntimeLog {
    private const val MAX = 500
    private val entries = ArrayDeque<JSONObject>()
    @Synchronized fun add(event: String, details: String = "") {
        if (entries.size >= MAX) entries.removeFirst()
        entries.addLast(JSONObject().put("time", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())).put("event", event).put("details", details))
    }
    @Synchronized fun json(): String = JSONArray(entries.toList()).toString()
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun addHardware(event: String, deviceId: String?, result: String = "", details: String = "") =
        add("hw_$event", listOfNotNull(deviceId?.let { "id=$it" }, result.takeIf { it.isNotBlank() }?.let { "result=$it" }, details.takeIf { it.isNotBlank() }).joinToString("; "))
}
