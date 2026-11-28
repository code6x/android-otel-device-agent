package dev.code6x.otelagent

import dev.code6x.otelagent.collect.ShellParsers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** The samples in test/resources/shell are real output of an Android 15 emulator (2026-11-28). */
class ShellParsersTest {
    private fun sample(name: String) = javaClass.getResource("/shell/$name")!!.readText()
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun processNames() {
        val names = ShellParsers.processNames(sample("ps.txt"))
        assertFalse("NAME" in names)
        assertTrue("com.android.systemui" in names)
        assertTrue(ShellParsers.isRunning(names, "com.android.systemui"))
        assertFalse(ShellParsers.isRunning(names, "com.example.notthere"))
        assertTrue(ShellParsers.isRunning(setOf("com.example.a:remote"), "com.example.a"))
        assertFalse(ShellParsers.isRunning(setOf("com.example.ab"), "com.example.a"))
    }

    @Test fun temperatures() {
        val t = ShellParsers.temperatures(sample("thermalservice.txt"))
        // The cached block has the same sensor; only the "Current temperatures" block counts.
        assertEquals(listOf(ShellParsers.Temperature("test temperature sensor", "skin", 30.8)), t)
        val many = "Current temperatures from HAL:\n" +
            "\tTemperature{mValue=41.5, mType=0, mName=cpu0, mStatus=0}\n" +
            "\tTemperature{mValue=NaN, mType=2, mName=battery, mStatus=0}\n" +
            "\tTemperature{mValue=30.0, mType=99, mName=x, mStatus=1}\n" +
            "Current cooling devices from HAL:\n" +
            "\tTemperature{mValue=1.0, mType=0, mName=after, mStatus=0}\n"
        assertEquals(
            listOf(ShellParsers.Temperature("cpu0", "cpu", 41.5), ShellParsers.Temperature("x", "99", 30.0)),
            ShellParsers.temperatures(many),
        )
    }

    @Test fun durations() {
        assertEquals(9L, ShellParsers.durationMs("+9ms"))
        assertEquals(1089L, ShellParsers.durationMs("+1s089ms"))
        assertEquals(((26 * 60 + 2) * 60 + 3) * 1000L + 4, ShellParsers.durationMs("+1d2h02m03s004ms"))
    }

    @Test fun batteryHistory() {
        val steps = ShellParsers.batteryHistory(sample("batterystats-history.txt"), utc)
        val start = 1795880401000L // 2026-11-28 15:40:01 UTC, the RESET:TIME
        assertEquals(start, steps.first().timeMs)
        assertEquals(100, steps.first().level)
        assertTrue(steps.first().plugged)
        val unplugged = steps.first { !it.plugged }
        assertEquals(80, steps.first { it.timeMs >= start + 9 }.level)
        assertEquals(start, unplugged.timeMs)
        val ac = steps.first { it.plug == "ac" }
        assertEquals(start + 2019, ac.timeMs)
        assertEquals(100, steps.last().level)
        assertEquals(start + 6063, steps.last().timeMs)

        val s = ShellParsers.summarize(steps, start + 1000, start + 10_000)!!
        assertEquals(80, s.levelStart)
        assertEquals(100, s.levelEnd)
        assertEquals(80, s.levelMin)
        assertEquals(100, s.levelMax)
        assertEquals(listOf(start + 2019 to "ac"), s.plugChanges)
        assertNull(ShellParsers.summarize(steps, start + 60_000, start + 70_000))
    }

    @Test fun historyStepsBeforeTimeAreSkipped() {
        val h = "    0 (2) 050 status=discharging\n" +
            "  +1s000ms (2) 049\n" +
            "  +2s000ms (14) 049 TIME:2026-11-28-10-00-00\n" +
            "  +3s000ms (2) 048 -plugged\n"
        val steps = ShellParsers.batteryHistory(h, utc)
        assertEquals(listOf(49, 48), steps.map { it.level })
        assertEquals(1000L, steps[1].timeMs - steps[0].timeMs)
    }
}
