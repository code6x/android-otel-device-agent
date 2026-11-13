package dev.code6x.otelagent.collect

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs

data class DeviceSnapshot(
    val memTotal: Long,
    val memAvailable: Long,
    val lowMemory: Boolean,
    val dataTotal: Long,
    val dataFree: Long,
)

/** RAM and the space on /data. */
class DeviceCollector(private val context: Context) : Collector {
    override val name = "device"

    override fun collect(): List<Reading> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val fs = StatFs(Environment.getDataDirectory().path)
        return map(DeviceSnapshot(mi.totalMem, mi.availMem, mi.lowMemory, fs.totalBytes, fs.availableBytes))
    }

    companion object {
        const val MOUNT = "/data"

        fun map(s: DeviceSnapshot): List<Reading> {
            val mount = mapOf("system.filesystem.mountpoint" to MOUNT)
            return listOf(
                Reading(Metrics.MEMORY_LIMIT, s.memTotal.toDouble()),
                Reading(Metrics.MEMORY_USAGE, s.memAvailable.toDouble(), mapOf("system.memory.state" to "free")),
                Reading(Metrics.MEMORY_USAGE, (s.memTotal - s.memAvailable).toDouble(), mapOf("system.memory.state" to "used")),
                Reading(Metrics.MEMORY_LOW, flag(s.lowMemory)),
                Reading(Metrics.FS_LIMIT, s.dataTotal.toDouble(), mount),
                Reading(Metrics.FS_USAGE, s.dataFree.toDouble(), mount + ("system.filesystem.state" to "free")),
                Reading(Metrics.FS_USAGE, (s.dataTotal - s.dataFree).toDouble(), mount + ("system.filesystem.state" to "used")),
            )
        }
    }
}
