package dev.code6x.otelagent.collect

import android.content.Context
import android.os.Build
import android.os.PowerManager

data class ThermalSnapshot(val status: Int?, val headroom: Float?)

/** Thermal status (Android 10+) and headroom (Android 11+), from PowerManager. */
class ThermalCollector(private val context: Context) : Collector {
    override val name = "thermal"

    override fun collect(): List<Reading> {
        val pm = context.getSystemService(PowerManager::class.java) ?: return emptyList()
        val status = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else null
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(0) else null
        return map(ThermalSnapshot(status, headroom))
    }

    companion object {
        fun map(s: ThermalSnapshot): List<Reading> {
            val out = mutableListOf<Reading>()
            s.status?.takeIf { it >= 0 }?.let { out += Reading(Metrics.THERMAL_STATUS, it.toDouble()) }
            // NaN: the phone has no value (or it was asked too often).
            s.headroom?.takeIf { !it.isNaN() }?.let { out += Reading(Metrics.THERMAL_HEADROOM, it.toDouble()) }
            return out
        }
    }
}
