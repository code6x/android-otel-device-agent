package dev.code6x.otelagent.collect

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

data class NetworkSnapshot(
    val connected: Boolean,
    val types: Set<String>,
    val wifiConnected: Boolean,
    val rssiDbm: Int?,
    val linkSpeedMbps: Int?,
)

/** Wi-Fi signal and link speed, active network and its transports. No SSID or BSSID: they need location. */
class NetworkCollector(private val context: Context) : Collector {
    override val name = "network"

    override fun collect(): List<Reading> = map(read())

    private fun read(): NetworkSnapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val types = mutableSetOf<String>()
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) types += "wifi"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) types += "cell"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) types += "wired"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) types += "vpn"
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) types += "bluetooth"
        }
        val wm = context.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val info = if (wm?.isWifiEnabled == true) wm.connectionInfo else null
        // networkId -1: not connected. RSSI -127: no value.
        @Suppress("DEPRECATION")
        val wifiConnected = info != null && info.networkId != -1
        return NetworkSnapshot(
            connected = caps != null,
            types = types,
            wifiConnected = wifiConnected,
            rssiDbm = info?.rssi?.takeIf { wifiConnected && it > -127 },
            linkSpeedMbps = info?.linkSpeed?.takeIf { wifiConnected && it > 0 },
        )
    }

    companion object {
        val TYPES = listOf("wifi", "cell", "wired", "vpn", "bluetooth")

        fun map(s: NetworkSnapshot): List<Reading> {
            val out = mutableListOf(Reading(Metrics.NETWORK_CONNECTED, flag(s.connected)))
            out += states(Metrics.NETWORK_TYPE, "network.connection.type", TYPES, s.types)
            out += Reading(Metrics.WIFI_CONNECTED, flag(s.wifiConnected))
            s.rssiDbm?.let { out += Reading(Metrics.WIFI_RSSI, it.toDouble()) }
            s.linkSpeedMbps?.let { out += Reading(Metrics.WIFI_LINK_SPEED, it.toDouble()) }
            return out
        }
    }
}
