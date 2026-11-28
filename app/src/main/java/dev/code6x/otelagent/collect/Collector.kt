package dev.code6x.otelagent.collect

import android.content.Context
import dev.code6x.otelagent.config.AgentConfig

/** One OTLP log record a collector wants sent. [timeMs] is the time of the event. */
data class LogEntry(val event: String, val body: String, val attributes: Map<String, String>, val timeMs: Long)

/** A group of readings that can be turned on or off, with its own interval. */
interface Collector {
    val name: String
    fun collect(): List<Reading>

    /** Log records made by the last [collect]; each is returned once. */
    fun takeLogs(): List<LogEntry> = emptyList()
}

object Collectors {
    /** [config] gives the config in use at each reading. */
    fun create(context: Context, config: () -> AgentConfig): List<Collector> {
        val c = context.applicationContext
        return listOf(
            BatteryCollector(c) { config().batteryCurrentUnit },
            PowerCollector(c),
            BootCollector(c),
            NetworkCollector(c),
            DeviceCollector(c),
            ThermalCollector(c),
            AppsCollector { config().appPackages },
            BootReasonCollector(c),
            BatteryStatsCollector(c) { config().collector("batterystats").intervalSeconds },
            ThermalSensorsCollector(),
        )
    }
}
