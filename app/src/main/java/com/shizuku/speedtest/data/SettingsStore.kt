package com.shizuku.speedtest.data

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("speedtest_settings", Context.MODE_PRIVATE)

    companion object {
        val RETENTION_OPTIONS: List<Int> = listOf(7, 30, 90, 180, 365, -1)

        fun retentionLabel(days: Int): String = when (days) {
            -1 -> "永久保存"
            1 -> "1 天"
            else -> "$days 天"
        }
    }

    var retentionDays: Int
        get() = prefs.getInt("retention_days", 90)
        set(value) {
            prefs.edit().putInt("retention_days", value).apply()
        }

    var lastNodeId: Long
        get() = prefs.getLong("last_node_id", -1L)
        set(value) {
            prefs.edit().putLong("last_node_id", value).apply()
        }

    var trafficTier: TrafficTier
        get() = runCatching {
            TrafficTier.valueOf(prefs.getString("traffic_tier", "STANDARD")!!)
        }.getOrDefault(TrafficTier.STANDARD)
        set(value) {
            prefs.edit().putString("traffic_tier", value.name).apply()
        }

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(prefs.getString("theme_mode", "SYSTEM")!!)
        }.getOrDefault(ThemeMode.SYSTEM)
        set(value) {
            prefs.edit().putString("theme_mode", value.name).apply()
        }

    var dynamicColor: Boolean
        get() = prefs.getBoolean("dynamic_color", true)
        set(value) {
            prefs.edit().putBoolean("dynamic_color", value).apply()
        }
}
