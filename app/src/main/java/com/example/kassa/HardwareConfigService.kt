package com.example.kassa

import android.content.Context
import org.json.JSONObject

class HardwareConfigService(context: Context) {
    private val prefs = context.getSharedPreferences("hardware_config", Context.MODE_PRIVATE)
    fun load(): HardwareConfig = prefs.getString("config", null)?.let {
        runCatching { HardwareConfig.fromJson(JSONObject(it)) }.getOrNull()
    } ?: HardwareConfig()
    fun save(config: HardwareConfig) { prefs.edit().putString("config", config.toJson().toString()).apply() }
}
