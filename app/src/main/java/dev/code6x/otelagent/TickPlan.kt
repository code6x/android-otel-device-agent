package dev.code6x.otelagent

/**
 * Which collectors read on a tick, and when the next tick is. Each collector has its own
 * interval; one timer runs to the next due time. Times are elapsedRealtime milliseconds.
 */
class TickPlan {
    private val nextDue = HashMap<String, Long>()

    /** Forget all due times: every enabled collector reads on the next tick. */
    fun reset() = nextDue.clear()

    /**
     * The collectors due at [now], from [intervals] (enabled collectors only, seconds).
     * A collector is due a little early ([SLACK_MS]), so a timer that fires early still reads.
     */
    fun due(now: Long, intervals: Map<String, Int>): List<String> {
        nextDue.keys.retainAll(intervals.keys)
        val due = intervals.keys.filter { k -> nextDue[k].let { it == null || now >= it - SLACK_MS } }
        due.forEach { nextDue[it] = now + intervals.getValue(it) * 1000L }
        return due
    }

    /** Milliseconds to the next tick, or null when no collector is enabled. */
    fun nextDelay(now: Long, intervals: Map<String, Int>): Long? {
        val times = intervals.keys.map { nextDue[it] ?: now }
        return times.minOrNull()?.let { maxOf(0L, it - now) }
    }

    companion object {
        const val SLACK_MS = 2000L
    }
}
