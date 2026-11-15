package dev.code6x.otelagent.collect

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

data class BootSnapshot(val elapsedMs: Long, val wallMs: Long, val bootCount: Int?) {
    /** Wall-clock time of the boot, in Unix seconds. */
    val bootTimeS: Long get() = (wallMs - elapsedMs) / 1000
}

class BootCollector(private val context: Context) : Collector {
    override val name = "boot"

    override fun collect(): List<Reading> = map(read(context))

    companion object {
        fun map(s: BootSnapshot): List<Reading> {
            val out = mutableListOf(
                Reading(Metrics.UPTIME, s.elapsedMs / 1000.0),
                Reading(Metrics.BOOT_TIME, s.bootTimeS.toDouble()),
            )
            s.bootCount?.let { out += Reading(Metrics.BOOT_COUNT, it.toDouble()) }
            return out
        }

        fun read(context: Context): BootSnapshot {
            val count = try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
            } catch (e: Settings.SettingNotFoundException) {
                null
            }
            return BootSnapshot(SystemClock.elapsedRealtime(), System.currentTimeMillis(), count)
        }

        /**
         * True when [now] is a boot the app has not reported yet. The boot count decides when the
         * phone has one; else the boot time (it moves a little with clock changes, so 2 min slack).
         */
        fun isNewBoot(lastCount: Int?, lastBootTimeS: Long?, now: BootSnapshot): Boolean {
            if (lastBootTimeS == null) return true
            if (now.bootCount != null && lastCount != null) return now.bootCount != lastCount
            return kotlin.math.abs(now.bootTimeS - lastBootTimeS) > 120
        }
    }
}
