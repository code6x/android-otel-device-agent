package dev.code6x.otelagent.collect

import android.content.Context

/** A group of readings that can be turned on or off, with its own interval. */
interface Collector {
    val name: String
    fun collect(): List<Reading>
}

object Collectors {
    fun create(context: Context): List<Collector> {
        val c = context.applicationContext
        return listOf(
            BatteryCollector(c),
            PowerCollector(c),
            BootCollector(c),
            NetworkCollector(c),
            DeviceCollector(c),
            ThermalCollector(c),
        )
    }
}
