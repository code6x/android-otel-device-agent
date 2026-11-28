package dev.code6x.otelagent.collect

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Pure parsers for the text that shell commands print. Tested with real emulator output. */
object ShellParsers {
    /** Process names from `ps -A -o NAME` (the header line is skipped). */
    fun processNames(ps: String): Set<String> =
        ps.lineSequence().drop(1).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** True when [pkg] runs: its main process, or one of its `pkg:name` processes. */
    fun isRunning(names: Set<String>, pkg: String): Boolean =
        pkg in names || names.any { it.startsWith("$pkg:") }

    data class Temperature(val sensor: String, val type: String, val celsius: Double)

    private val TEMPERATURE = Regex("""Temperature\{mValue=([^,]+), mType=(-?\d+), mName=(.*), mStatus=-?\d+\}""")

    /** Names of the HAL temperature types (android.os.Temperature TYPE_*). */
    private val TYPES = listOf(
        "cpu", "gpu", "battery", "skin", "usb_port", "power_amplifier", "bcl_voltage", "bcl_current",
        "bcl_percentage", "npu", "tpu", "display", "modem", "soc", "wifi", "camera", "flashlight",
        "speaker", "ambient", "pogo",
    )

    /** The "Current temperatures from HAL" block of `dumpsys thermalservice`. */
    fun temperatures(dump: String): List<Temperature> {
        val out = mutableListOf<Temperature>()
        var inBlock = false
        for (line in dump.lines()) {
            if (!line.startsWith("\t") && !line.startsWith(" ")) {
                inBlock = line.trim() == "Current temperatures from HAL:"
                continue
            }
            if (!inBlock) continue
            val m = TEMPERATURE.find(line) ?: continue
            val v = m.groupValues[1].toDoubleOrNull()?.takeIf { !it.isNaN() } ?: continue
            val t = m.groupValues[2].toInt()
            out += Temperature(m.groupValues[3], TYPES.getOrNull(t) ?: t.toString(), v)
        }
        return out
    }

    /** One battery history step: wall time, battery level (0..100) and power source. */
    data class BatteryStep(val timeMs: Long, val level: Int, val plugged: Boolean, val plug: String)

    // "   +1h02m03s004ms (2) 080 plug=ac +plugged"; the time is "0" for the first lines.
    // The level is missing on some lines ("0 (14) RESET:TIME: ...").
    private val HISTORY_LINE = Regex("""^\s*(0|\+[0-9dhms]+)\s+\(\d+\)(?:\s+(\d{3})\b)?(.*)$""")
    private val TIME_EVENT = Regex("""(?:RESET:)?TIME:\s*(\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2})""")
    private val DURATION_PART = Regex("""(\d+)(ms|d|h|m|s)""")

    /** "+1d2h03m04s005ms" in ms. */
    fun durationMs(s: String): Long {
        var ms = 0L
        for (m in DURATION_PART.findAll(s)) {
            val n = m.groupValues[1].toLong()
            ms += when (m.groupValues[2]) {
                "d" -> n * 86_400_000
                "h" -> n * 3_600_000
                "m" -> n * 60_000
                "s" -> n * 1000
                else -> n
            }
        }
        return ms
    }

    /**
     * Steps from `dumpsys batterystats --history`. Times are offsets from the history start;
     * each `TIME:` event (phone local time, in [tz]) sets the wall clock for the next steps.
     * Steps before the first `TIME:` are not returned (their wall time is not known).
     */
    fun batteryHistory(dump: String, tz: TimeZone): List<BatteryStep> = batteryHistory(dump.lineSequence(), tz).toList()

    /** As above, one line at a time (the output can be many MB on a real phone). */
    fun batteryHistory(lines: Sequence<String>, tz: TimeZone): Sequence<BatteryStep> = sequence {
        val fmt = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).apply { timeZone = tz }
        var offset = 0L
        var anchor: Pair<Long, Long>? = null // (offset, wall ms) of the last TIME: event
        var plugged = false
        var plug = "none"
        for (line in lines) {
            val m = HISTORY_LINE.find(line) ?: continue
            val t = m.groupValues[1]
            offset = if (t == "0") 0 else durationMs(t)
            val rest = m.groupValues[3]
            TIME_EVENT.find(rest)?.let { e -> fmt.parse(e.groupValues[1])?.let { anchor = offset to it.time } }
            for (word in rest.trim().split(Regex("\\s+"))) {
                when {
                    word == "+plugged" -> plugged = true
                    word == "-plugged" -> { plugged = false; plug = "none" }
                    word.startsWith("plug=") -> plug = word.substringAfter('=')
                }
            }
            val a = anchor ?: continue
            val level = m.groupValues[2].toIntOrNull() ?: continue
            yield(BatteryStep(a.second + (offset - a.first), level, plugged, if (plugged) plug else "none"))
        }
    }

    data class BatterySummary(
        val fromMs: Long,
        val toMs: Long,
        val levelStart: Int,
        val levelEnd: Int,
        val levelMin: Int,
        val levelMax: Int,
        /** Each change of the power source: (wall ms, plug or "none"). */
        val plugChanges: List<Pair<Long, String>>,
    )

    /** What happened in [fromMs, toMs]. Null when the history has no step in or before that time. */
    fun summarize(steps: List<BatteryStep>, fromMs: Long, toMs: Long): BatterySummary? = summarize(steps.asSequence(), fromMs, toMs)

    /** As above, in one pass; only the steps in the time are kept. */
    fun summarize(steps: Sequence<BatteryStep>, fromMs: Long, toMs: Long): BatterySummary? {
        var before: BatteryStep? = null
        val inside = mutableListOf<BatteryStep>()
        for (s in steps) {
            if (s.timeMs < fromMs) before = s else if (s.timeMs <= toMs) inside += s
        }
        if (inside.isEmpty()) {
            // A quiet time: nothing changed after the step before it.
            val b = before ?: return null
            return BatterySummary(fromMs, toMs, b.level, b.level, b.level, b.level, emptyList())
        }
        val changes = mutableListOf<Pair<Long, String>>()
        var last = before?.plug
        for (s in inside) {
            if (last != null && s.plug != last) changes += s.timeMs to s.plug
            last = s.plug
        }
        // The level at the start (the step before the time) counts for min and max too.
        val levels = (listOfNotNull(before) + inside).map { it.level }
        return BatterySummary(fromMs, toMs, (before ?: inside.first()).level, inside.last().level, levels.min(), levels.max(), changes)
    }
}
