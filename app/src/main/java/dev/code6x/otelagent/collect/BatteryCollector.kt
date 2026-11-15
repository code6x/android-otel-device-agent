package dev.code6x.otelagent.collect

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import dev.code6x.otelagent.config.CurrentUnit

/** Raw values as Android gives them; null when the phone does not report one. */
data class BatterySnapshot(
    val level: Int,
    val scale: Int,
    val voltageMv: Int?,
    val temperatureTenthsC: Int?,
    val status: Int,
    val plugged: Int,
    val health: Int,
    /** BATTERY_PROPERTY_CURRENT_NOW as the phone gives it: µA on most phones, mA on some. */
    val currentRaw: Long?,
    val chargeCounterMicroAh: Long?,
    val cycleCount: Int?,
)

class BatteryCollector(private val context: Context, private val currentUnit: () -> CurrentUnit) : Collector {
    override val name = "battery"

    /** True after a raw current too large to be mA: then this phone gives µA (see [unit]). */
    private var seenMicroA = false

    override fun collect(): List<Reading> {
        val s = read(context) ?: return emptyList()
        if (s.currentRaw != null && Math.abs(s.currentRaw) >= MICRO_A_MIN) seenMicroA = true
        return map(s, unit(currentUnit(), s.currentRaw, Build.MANUFACTURER, seenMicroA))
    }

    companion object {
        val STATUSES = listOf("charging", "discharging", "not_charging", "full", "unknown")
        val PLUGS = listOf("ac", "usb", "wireless", "dock", "none")
        val HEALTHS = listOf("good", "overheat", "dead", "over_voltage", "failure", "cold", "unknown")

        // Values of BatteryManager.BATTERY_STATUS_*, BATTERY_PLUGGED_* and BATTERY_HEALTH_*.
        fun status(v: Int) = when (v) {
            2 -> "charging"
            3 -> "discharging"
            4 -> "not_charging"
            5 -> "full"
            else -> "unknown"
        }

        /** The plug is a bit mask; more than one bit is rare. 0 is "none". */
        fun plugs(mask: Int): Set<String> {
            if (mask == 0) return setOf("none")
            val out = mutableSetOf<String>()
            if (mask and 1 != 0) out += "ac"
            if (mask and 2 != 0) out += "usb"
            if (mask and 4 != 0) out += "wireless"
            if (mask and 8 != 0) out += "dock"
            return out
        }

        fun health(v: Int) = when (v) {
            2 -> "good"
            3 -> "overheat"
            4 -> "dead"
            5 -> "over_voltage"
            6 -> "failure"
            7 -> "cold"
            else -> "unknown"
        }

        /** A raw value this large (50 A if it were mA, more than even 240 W fast charging) can only be µA. */
        const val MICRO_A_MIN = 50_000L

        /** Makers whose phones are known to give mA (OnePlus, OPPO and realme share one battery service). */
        val MILLI_A_MAKERS = setOf("oneplus", "oppo", "realme")

        /**
         * The unit of the raw current. A set unit wins. `auto`: µA when the value is too large
         * to be mA (now or earlier in this process, [seenMicroA]); else mA for the makers in
         * [MILLI_A_MAKERS]; else µA, as Android says.
         */
        fun unit(set: CurrentUnit, raw: Long?, manufacturer: String, seenMicroA: Boolean): CurrentUnit {
            if (set != CurrentUnit.AUTO) return set
            if (seenMicroA || (raw != null && Math.abs(raw) >= MICRO_A_MIN)) return CurrentUnit.MICRO_A
            return if (manufacturer.trim().lowercase() in MILLI_A_MAKERS) CurrentUnit.MILLI_A else CurrentUnit.MICRO_A
        }

        /** [unit] is the unit of the raw current (not AUTO; AUTO is read as µA). */
        fun map(s: BatterySnapshot, unit: CurrentUnit = CurrentUnit.MICRO_A): List<Reading> {
            val out = mutableListOf<Reading>()
            if (s.level >= 0 && s.scale > 0) out += Reading(Metrics.BATTERY_CHARGE, s.level.toDouble() / s.scale)
            s.voltageMv?.takeIf { it > 0 }?.let { out += Reading(Metrics.BATTERY_VOLTAGE, it / 1000.0) }
            s.temperatureTenthsC?.let { out += Reading(Metrics.BATTERY_TEMPERATURE, it / 10.0) }
            s.currentRaw?.let { out += Reading(Metrics.BATTERY_CURRENT, it / if (unit == CurrentUnit.MILLI_A) 1_000.0 else 1_000_000.0) }
            s.chargeCounterMicroAh?.takeIf { it > 0 }?.let { out += Reading(Metrics.BATTERY_CHARGE_COUNTER, it / 1_000_000.0) }
            s.cycleCount?.takeIf { it >= 0 }?.let { out += Reading(Metrics.BATTERY_CYCLES, it.toDouble()) }
            out += states(Metrics.BATTERY_STATUS, "battery.status", STATUSES, setOf(status(s.status)))
            out += states(Metrics.BATTERY_PLUG, "battery.plug", PLUGS, plugs(s.plugged))
            out += states(Metrics.BATTERY_HEALTH, "battery.health", HEALTHS, setOf(health(s.health)))
            return out
        }

        fun stickyIntent(context: Context): Intent? =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        /** True when the phone is on any charger. */
        fun isCharging(context: Context): Boolean =
            (stickyIntent(context)?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        fun read(context: Context): BatterySnapshot? {
            val i = stickyIntent(context) ?: return null
            val bm = context.getSystemService(BatteryManager::class.java)
            fun intExtra(key: String) = i.getIntExtra(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            val current = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?.takeIf { it != Long.MIN_VALUE && it != Int.MIN_VALUE.toLong() }
            val counter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it != Int.MIN_VALUE }?.toLong()
            val cycles = if (Build.VERSION.SDK_INT >= 34) intExtra(BatteryManager.EXTRA_CYCLE_COUNT) else null
            return BatterySnapshot(
                level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
                voltageMv = intExtra(BatteryManager.EXTRA_VOLTAGE),
                temperatureTenthsC = intExtra(BatteryManager.EXTRA_TEMPERATURE),
                status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 1),
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 1),
                currentRaw = current,
                chargeCounterMicroAh = counter,
                cycleCount = cycles,
            )
        }
    }
}
