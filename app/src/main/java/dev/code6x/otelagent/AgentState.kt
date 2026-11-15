package dev.code6x.otelagent

import dev.code6x.otelagent.collect.Reading
import java.util.concurrent.ConcurrentHashMap

/** What the status screen shows. Lives in the app process. */
object AgentState {
    data class CollectorState(val timeMs: Long, val readings: List<Reading>, val error: String?)

    val collectors = ConcurrentHashMap<String, CollectorState>()

    /** Start of this process (wall clock). */
    val processStartMs: Long = System.currentTimeMillis()

    @Volatile var lastReadingMs = 0L
    @Volatile var lastExportMs = 0L
    @Volatile var lastExportResult = ""
    @Volatile var timerMode = ""
    @Volatile var queueBytes = 0L
    @Volatile var queueFiles = 0
}
