package dev.code6x.otelagent.collect

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// The collectors that run shell commands through Shizuku (#4). Each throws
// ShizukuShell.NotAvailable when Shizuku is not ready; the service shows that as the state.

private fun prefs(context: Context) = context.getSharedPreferences("shizuku_collectors", Context.MODE_PRIVATE)

/** Is each configured package running: `ps -A -o NAME`. */
class AppsCollector(private val packages: () -> List<String>) : Collector {
    override val name = "apps"

    override fun collect(): List<Reading> {
        val pkgs = packages()
        if (pkgs.isEmpty()) return emptyList()
        return map(pkgs, ShellParsers.processNames(ShizukuShell.run("ps", "-A", "-o", "NAME")))
    }

    companion object {
        fun map(packages: List<String>, names: Set<String>): List<Reading> = packages.map {
            Reading(Metrics.APP_RUNNING, flag(ShellParsers.isRunning(names, it)), mapOf("app.package" to it))
        }
    }
}

/** Boot and last shutdown reason (`getprop`), as a metric and one log record per boot. */
class BootReasonCollector(private val context: Context) : Collector {
    override val name = "boot_reason"
    private var logs = emptyList<LogEntry>()

    override fun collect(): List<Reading> {
        val boot = ShizukuShell.run("getprop", "sys.boot.reason").trim()
        val shutdown = ShizukuShell.run("getprop", "sys.boot.reason.last").trim()
        val attrs = mapOf("device.boot.reason" to boot, "device.shutdown.reason" to shutdown)
        val p = prefs(context)
        val now = BootCollector.read(context)
        val lastCount = if (p.contains("br_boot_count")) p.getInt("br_boot_count", 0) else null
        val lastTime = if (p.contains("br_boot_time")) p.getLong("br_boot_time", 0) else null
        if (BootCollector.isNewBoot(lastCount, lastTime, now)) {
            logs = listOf(
                LogEntry("device.boot.reason", "boot reason: $boot; last shutdown reason: $shutdown", attrs, now.bootTimeS * 1000),
            )
            p.edit().apply {
                if (now.bootCount != null) putInt("br_boot_count", now.bootCount) else remove("br_boot_count")
                putLong("br_boot_time", now.bootTimeS)
            }.apply()
        }
        return listOf(Reading(Metrics.BOOT_REASON, 1.0, attrs))
    }

    override fun takeLogs(): List<LogEntry> = logs.also { logs = emptyList() }
}

/**
 * After a boot, or a gap with no reading (more than 3 intervals, at least 5 min), sends one
 * log record: what `dumpsys batterystats --history` says about the battery in that time.
 * A gap seen while Shizuku is not ready is kept and sent when Shizuku is ready.
 */
class BatteryStatsCollector(private val context: Context, private val intervalSeconds: () -> Int) : Collector {
    override val name = "batterystats"
    private var logs = emptyList<LogEntry>()

    override fun collect(): List<Reading> {
        val p = prefs(context)
        val nowMs = System.currentTimeMillis()
        val boot = BootCollector.read(context)
        val lastSeen = p.getLong("bs_last_seen", 0)
        val gapMs = maxOf(3L * intervalSeconds() * 1000, 5 * 60_000L)
        val newBoot = lastSeen > 0 && kotlin.math.abs(boot.bootTimeS - p.getLong("bs_boot_time", 0)) > 120
        val gap = lastSeen > 0 && (newBoot || nowMs - lastSeen > gapMs)
        p.edit().apply {
            if (gap && !p.contains("bs_gap_from")) putLong("bs_gap_from", lastSeen)
            putLong("bs_last_seen", nowMs)
            putLong("bs_boot_time", boot.bootTimeS)
        }.apply()
        val from = p.getLong("bs_gap_from", 0)
        if (from == 0L) {
            // Nothing to send; still show when Shizuku is not ready.
            ShizukuShell.state().let { if (it != ShizukuShell.State.READY) throw ShizukuShell.NotAvailable(it) }
            return emptyList()
        }
        val steps = ShellParsers.batteryHistory(ShizukuShell.run("dumpsys", "batterystats", "--history"), TimeZone.getDefault())
        logs = listOf(record(ShellParsers.summarize(steps, from, nowMs), from, nowMs))
        p.edit().remove("bs_gap_from").apply()
        return emptyList()
    }

    override fun takeLogs(): List<LogEntry> = logs.also { logs = emptyList() }

    companion object {
        private fun iso(ms: Long) =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        fun record(s: ShellParsers.BatterySummary?, fromMs: Long, toMs: Long): LogEntry {
            val attrs = linkedMapOf("device.agent.gap.start" to iso(fromMs), "device.agent.gap.end" to iso(toMs))
            val body = if (s == null) {
                "no battery history for ${iso(fromMs)} to ${iso(toMs)} (the history was reset or is empty)"
            } else {
                attrs["device.battery.level.start"] = s.levelStart.toString()
                attrs["device.battery.level.end"] = s.levelEnd.toString()
                attrs["device.battery.level.min"] = s.levelMin.toString()
                attrs["device.battery.level.max"] = s.levelMax.toString()
                val plugs = s.plugChanges.joinToString { "${iso(it.first)} ${it.second}" }
                attrs["device.battery.plug.changes"] = plugs
                "battery from ${iso(fromMs)} to ${iso(toMs)}: level ${s.levelStart}% to ${s.levelEnd}% " +
                    "(min ${s.levelMin}%, max ${s.levelMax}%); power source changes: ${plugs.ifEmpty { "none" }}"
            }
            return LogEntry("device.battery.history", body, attrs, toMs)
        }
    }
}

/** Temperature of each sensor, from `dumpsys thermalservice`. */
class ThermalSensorsCollector : Collector {
    override val name = "thermal_sensors"

    override fun collect(): List<Reading> =
        ShellParsers.temperatures(ShizukuShell.run("dumpsys", "thermalservice")).map {
            Reading(Metrics.THERMAL_TEMPERATURE, it.celsius, mapOf("device.thermal.sensor" to it.sensor, "device.thermal.type" to it.type))
        }
}
