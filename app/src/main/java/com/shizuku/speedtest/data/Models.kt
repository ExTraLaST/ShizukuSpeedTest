package com.shizuku.speedtest.data

data class KnownNetwork(
    val id: Long = 0L,
    val ssid: String,
    val bssid: String,
    val alias: String = "",
    val defaultNodeId: Long? = null,
    val lastSeen: Long = 0L
) {
    fun isMobile(): Boolean = ssid.isEmpty() && bssid.isEmpty()

    val displayName: String
        get() = alias.ifBlank { if (isMobile()) "移动数据" else ssid }
}

data class SpeedNode(
    val id: Long = 0L,
    val name: String,
    val baseUrl: String,
    val protocol: String = PROTOCOL_CLOUDFLARE,
    val builtin: Boolean = false
) {
    companion object {
        const val PROTOCOL_CLOUDFLARE = "cloudflare"
        const val PROTOCOL_LIBRESPEED = "librespeed"
        const val PROTOCOL_LIBRESPEED_ROOT = "librespeed_root"
        const val PROTOCOL_LOCAL = "local"
    }

    val protocolLabel: String
        get() = when (protocol) {
            PROTOCOL_LOCAL -> "本地服务"
            PROTOCOL_LIBRESPEED, PROTOCOL_LIBRESPEED_ROOT -> "LibreSpeed"
            else -> "Cloudflare 兼容"
        }

    fun latencyUrl(cacheBuster: Long? = null): String {
        val base = when (protocol) {
            PROTOCOL_LOCAL -> "$baseUrl/ping"
            PROTOCOL_LIBRESPEED -> "$baseUrl/backend/empty.php"
            PROTOCOL_LIBRESPEED_ROOT -> "$baseUrl/empty.php"
            else -> "$baseUrl/__down?bytes=0"
        }
        return if (cacheBuster != null) {
            val sep = if (base.contains("?")) "&" else "?"
            "$base${sep}r=$cacheBuster"
        } else {
            base
        }
    }

    fun downloadUrl(bytes: Long, cacheBuster: Long? = null): String {
        val base = when (protocol) {
            PROTOCOL_LOCAL -> "$baseUrl/down?bytes=$bytes"
            PROTOCOL_LIBRESPEED -> {
                val mb = ((bytes + 1_048_575L) / 1_048_576L).coerceIn(1L, 1024L)
                "$baseUrl/backend/garbage.php?ckSize=$mb"
            }
            PROTOCOL_LIBRESPEED_ROOT -> {
                val mb = ((bytes + 1_048_575L) / 1_048_576L).coerceIn(1L, 1024L)
                "$baseUrl/garbage.php?ckSize=$mb"
            }
            else -> "$baseUrl/__down?bytes=$bytes"
        }
        return if (cacheBuster != null) "$base&r=$cacheBuster" else base
    }

    fun uploadUrl(): String = when (protocol) {
        PROTOCOL_LOCAL -> "$baseUrl/up"
        PROTOCOL_LIBRESPEED -> "$baseUrl/backend/empty.php"
        PROTOCOL_LIBRESPEED_ROOT -> "$baseUrl/empty.php"
        else -> "$baseUrl/__up"
    }
}

data class TestResult(
    val id: Long = 0L,
    val networkId: Long,
    val nodeId: Long,
    val timestamp: Long,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val latencyMs: Double,
    val jitterMs: Double,
    val packetLoss: Double,
    val rssi: Int,
    val band: String,
    val linkSpeedMbps: Int,
    val serverName: String,
    val gatewayLatencyMs: Double? = null,
    val gatewayJitterMs: Double? = null
)

enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("白天模式"),
    DARK("黑夜模式")
}

enum class TrafficTier(
    val label: String,
    val durationSec: Int,
    val concurrency: Int
) {
    LIGHT("轻量", 5, 2),
    STANDARD("标准", 10, 4),
    FULL("完整", 15, 6)
}
