package com.shizuku.speedtest.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

data class CurrentNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val linkSpeedMbps: Int,
    val frequencyMhz: Int,
    val gatewayIp: String?,
    val isWifi: Boolean,
    val ssidKnown: Boolean = true
) {
    val band: String
        get() = when {
            !isWifi -> "移动数据"
            frequencyMhz in 2400 until 2500 -> "2.4GHz"
            frequencyMhz in 5000 until 6000 -> "5GHz"
            frequencyMhz >= 5925 -> "6GHz"
            else -> "未知"
        }

    val signalPercent: Int
        get() = when {
            !isWifi -> 0
            rssi >= -50 -> 100
            rssi <= -90 -> 0
            else -> ((rssi + 90) * 100) / 40
        }
}

fun hasWifiPermission(context: Context): Boolean {
    val appCtx = context.applicationContext
    val fineLocation = ContextCompat.checkSelfPermission(
        appCtx,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val nearbyWifi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            appCtx,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    return fineLocation || nearbyWifi
}

fun isLocationEnabled(context: Context): Boolean {
    val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return false
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    } catch (_: Exception) {
        false
    }
}

@Suppress("DEPRECATION")
fun getCurrentNetwork(context: Context): CurrentNetwork? {
    val appCtx = context.applicationContext
    val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(active) ?: return null

    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
        return CurrentNetwork(
            ssid = "",
            bssid = "",
            rssi = 0,
            linkSpeedMbps = 0,
            frequencyMhz = 0,
            gatewayIp = null,
            isWifi = false
        )
    }

    val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val info = wm.connectionInfo
    val gwInt = wm.dhcpInfo.gateway
    val gatewayIp = if (gwInt != 0) intToIp(gwInt) else null

    var rawSsid = ""
    var rawBssid = ""
    if (hasWifiPermission(appCtx)) {
        val s = info.ssid?.removeSurrounding("\"") ?: ""
        rawSsid = if (s.isNotEmpty() && s != "<unknown ssid>") s else ""
        val b = info.bssid ?: ""
        rawBssid = if (b.isNotEmpty() && b != "02:00:00:00:00:00") b else ""
    }

    val ssidKnown = rawSsid.isNotEmpty()
    val effectiveSsid: String
    val effectiveBssid: String
    if (!ssidKnown && gatewayIp != null) {
        effectiveSsid = "网关 $gatewayIp"
        effectiveBssid = gatewayIp
    } else {
        effectiveSsid = rawSsid
        effectiveBssid = rawBssid
    }

    return CurrentNetwork(
        ssid = effectiveSsid,
        bssid = effectiveBssid,
        rssi = info.rssi,
        linkSpeedMbps = info.linkSpeed,
        frequencyMhz = info.frequency,
        gatewayIp = gatewayIp,
        isWifi = true,
        ssidKnown = ssidKnown
    )
}

private fun intToIp(ip: Int): String =
    "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
