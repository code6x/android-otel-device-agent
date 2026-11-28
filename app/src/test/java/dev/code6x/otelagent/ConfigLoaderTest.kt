package dev.code6x.otelagent

import dev.code6x.otelagent.config.AgentConfig
import dev.code6x.otelagent.config.ConfigException
import dev.code6x.otelagent.config.ConfigLoader
import dev.code6x.otelagent.config.CurrentUnit
import dev.code6x.otelagent.config.Source
import dev.code6x.otelagent.config.WakePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigLoaderTest {
    private fun local(json: String) = ConfigLoader.load(listOf(Source.LOCAL_FILE to json))

    private fun assertRejected(json: String, part: String) {
        try {
            local(json)
            fail("not rejected: $json")
        } catch (e: ConfigException) {
            assertTrue("message '${e.message}' has '$part'", e.message!!.contains(part))
        }
    }

    @Test fun defaults() {
        val c = ConfigLoader.load(emptyList()).config
        assertEquals(WakePolicy.WHEN_CHARGING, c.wakePolicy)
        assertEquals("", c.exporter.endpoint)
        assertEquals("http/protobuf", c.exporter.protocol)
        assertEquals(10, c.exporter.timeoutSeconds)
        assertEquals(
            listOf("battery", "power", "boot", "network", "device", "thermal", "apps", "boot_reason", "batterystats", "thermal_sensors"),
            c.collectors.keys.toList(),
        )
        c.collectors.forEach { (name, it) ->
            // The Shizuku collectors are off by default (#4); the others are on.
            assertEquals(name, name !in AgentConfig.SHIZUKU_COLLECTORS, it.enabled)
            assertEquals(60, it.intervalSeconds)
        }
        assertTrue(c.appPackages.isEmpty())
        assertEquals(20L * 1024 * 1024, c.buffer.maxBytes)
        assertEquals(7L * 86400, c.buffer.maxAgeSeconds)
        assertTrue(c.attributes.isEmpty())
        assertEquals(CurrentUnit.AUTO, c.batteryCurrentUnit)
    }

    @Test fun batteryCurrentUnitValues() {
        fun unit(v: String) = local("""{"collectors": {"battery": {"current_unit": "$v"}}}""").config.batteryCurrentUnit
        assertEquals(CurrentUnit.AUTO, unit("auto"))
        assertEquals(CurrentUnit.MICRO_A, unit("uA"))
        assertEquals(CurrentUnit.MILLI_A, unit("mA"))
        assertTrue(local("""{"collectors": {"battery": {"current_unit": "mA"}}}""").warnings.isEmpty())
        assertRejected("""{"collectors": {"battery": {"current_unit": "MA"}}}""", "current_unit")
        assertRejected("""{"collectors": {"battery": {"current_unit": 1000}}}""", "current_unit")
        // Only the battery collector has this key.
        assertEquals(1, local("""{"collectors": {"power": {"current_unit": "mA"}}}""").warnings.size)
    }

    @Test fun localFileOverridesDefaultsAndKeepsSources() {
        val l = local(
            """
            {"exporter": {"endpoint": "https://otlp.example.com/v1/metrics", "headers": {"Authorization": "Bearer x"}},
             "resource": {"device.id": "phone-1", "attributes": {"location": "lobby"}},
             "collectors": {"thermal": {"enabled": false}, "battery": {"interval": 30}},
             "wake_policy": "always"}
            """,
        )
        val c = l.config
        assertEquals("https://otlp.example.com/v1/metrics", c.exporter.endpoint)
        assertEquals(mapOf("Authorization" to "Bearer x"), c.exporter.headers)
        assertEquals("phone-1", c.deviceId)
        assertEquals(mapOf("location" to "lobby"), c.attributes)
        assertFalse(c.collector("thermal").enabled)
        assertEquals(60, c.collector("thermal").intervalSeconds)
        assertEquals(30, c.collector("battery").intervalSeconds)
        assertTrue(c.collector("battery").enabled)
        assertEquals(WakePolicy.ALWAYS, c.wakePolicy)

        fun source(vararg p: String) = l.entries.first { it.path == p.toList() }.source
        assertEquals(Source.LOCAL_FILE, source("exporter", "endpoint"))
        assertEquals(Source.DEFAULT, source("exporter", "timeout"))
        assertEquals(Source.LOCAL_FILE, source("collectors", "battery", "interval"))
        assertEquals(Source.DEFAULT, source("collectors", "battery", "enabled"))
        assertEquals(Source.LOCAL_FILE, source("resource", "device.id"))
        assertTrue(l.warnings.isEmpty())
    }

    @Test fun wakePolicyValues() {
        assertEquals(WakePolicy.NEVER, local("""{"wake_policy": "never"}""").config.wakePolicy)
        assertEquals(WakePolicy.WHEN_CHARGING, local("""{"wake_policy": "when_charging"}""").config.wakePolicy)
        assertEquals(WakePolicy.ALWAYS, local("""{"wake_policy": "always"}""").config.wakePolicy)
        assertRejected("""{"wake_policy": "sometimes"}""", "wake_policy")
    }

    @Test fun wakePolicyWakes() {
        assertFalse(WakePolicy.NEVER.wakes(true))
        assertFalse(WakePolicy.NEVER.wakes(false))
        assertTrue(WakePolicy.WHEN_CHARGING.wakes(true))
        assertFalse(WakePolicy.WHEN_CHARGING.wakes(false))
        assertTrue(WakePolicy.ALWAYS.wakes(true))
        assertTrue(WakePolicy.ALWAYS.wakes(false))
    }

    @Test fun unknownKeysAreWarnedNotUsed() {
        val l = local("""{"colectors": {"battery": {"enabled": false}}, "collectors": {"gps": {"enabled": true}}}""")
        assertEquals(2, l.warnings.size)
        assertTrue(l.config.collector("battery").enabled)
    }

    @Test fun appPackages() {
        val c = local("""{"collectors": {"apps": {"enabled": true, "packages": " com.example.a, org.example.b_2 ,"}}}""").config
        assertEquals(listOf("com.example.a", "org.example.b_2"), c.appPackages)
        assertTrue(c.collector("apps").enabled)
        assertRejected("""{"collectors": {"apps": {"packages": "com.example.a; rm -rf /"}}}""", "not a package name")
        assertRejected("""{"collectors": {"apps": {"packages": "nodot"}}}""", "not a package name")
        assertTrue(local("""{"collectors": {"apps": {"enabled": true}}}""").warnings.any { it.contains("packages is empty") })
        assertTrue(local("""{"collectors": {"apps": {"enabled": false}}}""").warnings.isEmpty())
    }

    @Test fun badValuesAreRejected() {
        assertRejected("not json", "not valid JSON")
        assertRejected("""{"collectors": {"battery": {"interval": 5}}}""", "from 10 to 86400")
        assertRejected("""{"collectors": {"battery": {"interval": 60.5}}}""", "whole number")
        assertRejected("""{"collectors": {"battery": {"interval": "60"}}}""", "must be a number")
        assertRejected("""{"collectors": {"battery": {"enabled": "yes"}}}""", "true or false")
        assertRejected("""{"exporter": {"protocol": "grpc"}}""", "http/protobuf")
        assertRejected("""{"exporter": {"endpoint": "otlp.example.com"}}""", "http://")
        assertRejected("""{"exporter": {"headers": {"X-Token": 5}}}""", "must be text")
        assertRejected("""{"resource": {"attributes": ["a"]}}""", "list")
        assertRejected("""{"buffer": {"max_bytes": 10}}""", "buffer > max_bytes")
    }

    @Test fun headerValuesAreHiddenOnScreen() {
        val l = local("""{"exporter": {"headers": {"Authorization": "Bearer secret"}}}""")
        val text = l.entries.joinToString("\n") { ConfigLoader.display(it) }
        assertFalse(text.contains("secret"))
        assertTrue(text.contains("exporter > headers > Authorization = (hidden)  [local file]"))
    }
}
