package dev.code6x.otelagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TickPlanTest {
    @Test fun eachCollectorHasItsOwnInterval() {
        val p = TickPlan()
        val iv = mapOf("battery" to 30, "device" to 60)
        assertEquals(listOf("battery", "device"), p.due(0, iv))
        assertEquals(30_000L, p.nextDelay(0, iv))
        assertEquals(listOf("battery"), p.due(30_000, iv))
        assertEquals(listOf("battery", "device"), p.due(60_000, iv))
    }

    @Test fun aTimerThatFiresALittleEarlyStillReads() {
        val p = TickPlan()
        val iv = mapOf("battery" to 60)
        p.due(0, iv)
        assertEquals(listOf("battery"), p.due(59_000, iv))
        assertEquals(emptyList<String>(), p.due(100_000, iv)) // next due 119 000
    }

    @Test fun aCollectorTurnedOffIsNotRead() {
        val p = TickPlan()
        p.due(0, mapOf("battery" to 60, "thermal" to 60))
        assertEquals(listOf("battery"), p.due(60_000, mapOf("battery" to 60)))
        assertNull(p.nextDelay(60_000, emptyMap()))
    }

    @Test fun resetReadsAllAtOnce() {
        val p = TickPlan()
        val iv = mapOf("battery" to 60)
        p.due(0, iv)
        p.reset()
        assertEquals(0L, p.nextDelay(1000, iv))
        assertEquals(listOf("battery"), p.due(1000, iv))
    }
}
