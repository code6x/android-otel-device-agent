package dev.code6x.otelagent

import dev.code6x.otelagent.config.ConfigException
import dev.code6x.otelagent.config.Layers
import dev.code6x.otelagent.config.RemoteResult
import dev.code6x.otelagent.config.Source
import dev.code6x.otelagent.config.WakePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LayersTest {
    private val file = Source.LOCAL_FILE to """{"exporter": {"endpoint": "https://collector.example/v1/metrics"},
        "opamp": {"endpoint": "wss://opamp.example/v1/opamp"}}"""

    private fun source(l: dev.code6x.otelagent.config.LoadedConfig, vararg path: String) =
        l.entries.first { it.path == path.toList() }.source

    @Test fun laterLayerWins_andSourceIsKept() {
        val managed = Source.MANAGED to Layers.managedToJson(mapOf(Layers.KEY_INTERVAL to 30))!!
        val opamp = """{"collectors": {"battery": {"interval": 15}}}"""
        val l = Layers.merge(listOf(file, managed), opamp)
        assertEquals(15, l.config.collector("battery").intervalSeconds)
        assertEquals(30, l.config.collector("power").intervalSeconds)
        assertEquals(Source.OPAMP, source(l, "collectors", "battery", "interval"))
        assertEquals(Source.MANAGED, source(l, "collectors", "power", "interval"))
        assertEquals(Source.LOCAL_FILE, source(l, "exporter", "endpoint"))
        assertEquals(Source.DEFAULT, source(l, "buffer", "max_bytes"))
    }

    @Test fun wakePolicy_fromManaged() {
        val managed = Source.MANAGED to Layers.managedToJson(mapOf(Layers.KEY_WAKE_POLICY to "always"))!!
        val l = Layers.merge(listOf(file, managed), null)
        assertEquals(WakePolicy.ALWAYS, l.config.wakePolicy)
        assertEquals(Source.MANAGED, source(l, "wake_policy"))
    }

    @Test fun wakePolicy_fromOpamp() {
        val r = Layers.checkRemote(listOf(file), """{"wake_policy": "never"}""")
        assertEquals(WakePolicy.NEVER, (r as RemoteResult.Applied).loaded.config.wakePolicy)
        val l = Layers.merge(listOf(file), """{"wake_policy": "never"}""")
        assertEquals(WakePolicy.NEVER, l.config.wakePolicy)
        assertEquals(Source.OPAMP, source(l, "wake_policy"))
    }

    @Test fun brokenRemote_isRefused() {
        for (bad in listOf("not json", """{"wake_policy": "sometimes"}""", """{"collectors": {"battery": {"interval": 1}}}""")) {
            assertTrue(bad, Layers.checkRemote(listOf(file), bad) is RemoteResult.Refused)
        }
        // No endpoint after the merge.
        val r = Layers.checkRemote(emptyList(), """{"wake_policy": "never"}""")
        assertEquals("no exporter endpoint", (r as RemoteResult.Refused).reason)
    }

    @Test fun brokenStoredRemote_lastGoodStays() {
        val l = Layers.merge(listOf(file), "not json")
        assertEquals(WakePolicy.WHEN_CHARGING, l.config.wakePolicy)
        assertTrue(l.warnings.any { it.startsWith("OpAMP config not used") })
    }

    @Test fun emptyRemote_isKept() {
        assertEquals(RemoteResult.Kept, Layers.checkRemote(listOf(file), ""))
        assertEquals(RemoteResult.Kept, Layers.checkRemote(listOf(file), "  \n"))
    }

    @Test fun opampCannotChangeItsOwnConnection() {
        val l = Layers.merge(listOf(file), """{"opamp": {"enabled": false, "endpoint": "ws://other.example"}}""")
        assertTrue(l.config.opamp.on)
        assertEquals("wss://opamp.example/v1/opamp", l.config.opamp.endpoint)
    }

    @Test fun opampOff_layerNotUsed() {
        val managed = Source.MANAGED to Layers.managedToJson(mapOf(Layers.KEY_OPAMP_ENABLED to "false"))!!
        val l = Layers.merge(listOf(file, managed), """{"wake_policy": "never"}""")
        assertFalse(l.config.opamp.on)
        assertEquals(WakePolicy.WHEN_CHARGING, l.config.wakePolicy)
        // No endpoint: off too.
        assertFalse(Layers.merge(emptyList(), null).config.opamp.on)
    }

    @Test fun brokenManaged_isLeftOut() {
        val l = Layers.merge(listOf(file, Source.MANAGED to """{"wake_policy": "x"}"""), null)
        assertEquals(WakePolicy.WHEN_CHARGING, l.config.wakePolicy)
        assertEquals("https://collector.example/v1/metrics", l.config.exporter.endpoint)
        assertTrue(l.warnings.any { it.startsWith("managed config not used") })
    }

    @Test fun brokenManaged_doesNotBlockRemote() {
        val base = Layers.usable(listOf(file, Source.MANAGED to """{"wake_policy": "x"}"""))
        assertEquals(listOf(file), base)
        assertTrue(Layers.checkRemote(base, """{"wake_policy": "never"}""") is RemoteResult.Applied)
    }

    @Test fun managedToJson_keys() {
        assertNull(Layers.managedToJson(emptyMap()))
        // What an admin tool sends for keys it does not set.
        assertNull(Layers.managedToJson(mapOf(Layers.KEY_ENDPOINT to "", Layers.KEY_INTERVAL to 0, Layers.KEY_CONFIG_JSON to "")))
        val json = Layers.managedToJson(mapOf(
            Layers.KEY_CONFIG_JSON to """{"buffer": {"max_age": 3600}, "exporter": {"timeout": 5}}""",
            Layers.KEY_ENDPOINT to " https://m.example/v1/metrics ",
            Layers.KEY_OPAMP_ENDPOINT to "ws://o.example/v1/opamp",
            Layers.KEY_OPAMP_ENABLED to "true",
        ))!!
        val c = Layers.merge(listOf(Source.MANAGED to json), null).config
        assertEquals(3600L, c.buffer.maxAgeSeconds)
        assertEquals(5, c.exporter.timeoutSeconds)
        assertEquals("https://m.example/v1/metrics", c.exporter.endpoint)
        assertTrue(c.opamp.on)
        try {
            Layers.managedToJson(mapOf(Layers.KEY_CONFIG_JSON to "{"))
            fail()
        } catch (_: ConfigException) {
        }
    }

    @Test fun effectiveJson_hidesHeaders() {
        val l = Layers.merge(listOf(Source.LOCAL_FILE to """{"exporter": {"headers": {"Authorization": "Bearer x"}}}"""), null)
        val j = org.json.JSONObject(dev.code6x.otelagent.config.ConfigLoader.effectiveJson(l.entries))
        assertEquals("(hidden)", j.getJSONObject("exporter").getJSONObject("headers").getString("Authorization"))
        assertEquals("when_charging", j.getString("wake_policy"))
        assertEquals(60, j.getJSONObject("collectors").getJSONObject("battery").getInt("interval"))
    }
}
