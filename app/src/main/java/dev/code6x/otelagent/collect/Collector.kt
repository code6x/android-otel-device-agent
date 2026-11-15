package dev.code6x.otelagent.collect

import android.content.Context
import dev.code6x.otelagent.config.CurrentUnit

/** A group of readings that can be turned on or off, with its own interval. */
interface Collector {
    val name: String
    fun collect(): List<Reading>
}

object Collectors {
    /** [currentUnit] gives the `collectors > battery > current_unit` value in use at each reading. */
    fun create(context: Context, currentUnit: () -> CurrentUnit): List<Collector> {
        val c = context.applicationContext
        return listOf(
            BatteryCollector(c, currentUnit),
            PowerCollector(c),
            BootCollector(c),
            NetworkCollector(c),
            DeviceCollector(c),
            ThermalCollector(c),
        )
    }
}
