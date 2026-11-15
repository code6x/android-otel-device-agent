package dev.code6x.otelagent.collect

import android.content.Context
import android.os.PowerManager

data class PowerSnapshot(val screenOn: Boolean, val powerSave: Boolean, val idle: Boolean)

class PowerCollector(private val context: Context) : Collector {
    override val name = "power"

    override fun collect(): List<Reading> {
        val pm = context.getSystemService(PowerManager::class.java) ?: return emptyList()
        return map(PowerSnapshot(pm.isInteractive, pm.isPowerSaveMode, pm.isDeviceIdleMode))
    }

    companion object {
        fun map(s: PowerSnapshot) = listOf(
            Reading(Metrics.SCREEN_ON, flag(s.screenOn)),
            Reading(Metrics.POWER_SAVE, flag(s.powerSave)),
            Reading(Metrics.IDLE, flag(s.idle)),
        )
    }
}
