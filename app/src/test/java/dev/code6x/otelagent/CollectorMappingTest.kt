package dev.code6x.otelagent

import dev.code6x.otelagent.collect.BatteryCollector
import dev.code6x.otelagent.collect.BatterySnapshot
import dev.code6x.otelagent.collect.BootCollector
import dev.code6x.otelagent.collect.BootSnapshot
import dev.code6x.otelagent.collect.DeviceCollector
import dev.code6x.otelagent.collect.DeviceSnapshot
import dev.code6x.otelagent.collect.Metrics
import dev.code6x.otelagent.collect.NetworkCollector
import dev.code6x.otelagent.collect.NetworkSnapshot
import dev.code6x.otelagent.collect.PowerCollector
import dev.code6x.otelagent.collect.PowerSnapshot
import dev.code6x.otelagent.collect.Reading
import dev.code6x.otelagent.collect.ThermalCollector
import dev.code6x.otelagent.collect.ThermalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorMappingTest {
    private fun List<Reading>.value(name: String, vararg attrs: Pair<String, String>): Double? =
        firstOrNull { it.def.name == name && it.attributes == attrs.toMap() }?.value

    private fun List<Reading>.current(name: String, attr: String): Set<String> =
        filter { it.def.name == name && it.value == 1.0 }.map { it.attributes.getValue(attr) }.toSet()

    @Test fun battery() {
        val r = BatteryCollector.map(
            BatterySnapshot(
                level = 87, scale = 100, voltageMv = 4123, temperatureTenthsC = 312, status = 2, plugged = 1,
                health = 2, currentMicroA = -250_000, chargeCounterMicroAh = 3_100_000, cycleCount = 42,
            ),
        )
        assertEquals(0.87, r.value("hw.battery.charge")!!, 1e-9)
        assertEquals(4.123, r.value("device.battery.voltage")!!, 1e-9)
        assertEquals(31.2, r.value("device.battery.temperature")!!, 1e-9)
        assertEquals(-0.25, r.value("device.battery.current")!!, 1e-9)
        assertEquals(3.1, r.value("device.battery.charge_counter")!!, 1e-9)
        assertEquals(42.0, r.value("device.battery.cycle_count")!!, 0.0)
        assertEquals(setOf("charging"), r.current("device.battery.status", "battery.status"))
        assertEquals(setOf("ac"), r.current("device.battery.plug", "battery.plug"))
        assertEquals(setOf("good"), r.current("device.battery.health", "battery.health"))
        // Every state is sent, so a state that ends goes to 0 at once.
        assertEquals(0.0, r.value("device.battery.plug", "battery.plug" to "none")!!, 0.0)
        assertEquals(5, r.count { it.def.name == "device.battery.plug" })
    }

    @Test fun batteryWithoutOptionalValues() {
        val r = BatteryCollector.map(
            BatterySnapshot(50, 100, null, null, 3, 0, 1, null, null, null),
        )
        assertNull(r.value("device.battery.voltage"))
        assertNull(r.value("device.battery.current"))
        assertNull(r.value("device.battery.cycle_count"))
        assertEquals(setOf("discharging"), r.current("device.battery.status", "battery.status"))
        assertEquals(setOf("none"), r.current("device.battery.plug", "battery.plug"))
        assertEquals(setOf("unknown"), r.current("device.battery.health", "battery.health"))
    }

    @Test fun batteryPlugTypes() {
        assertEquals(setOf("usb"), BatteryCollector.plugs(2))
        assertEquals(setOf("wireless"), BatteryCollector.plugs(4))
        assertEquals(setOf("dock"), BatteryCollector.plugs(8))
        assertEquals(setOf("none"), BatteryCollector.plugs(0))
        assertEquals("full", BatteryCollector.status(5))
        assertEquals("not_charging", BatteryCollector.status(4))
        assertEquals("overheat", BatteryCollector.health(3))
        assertEquals("cold", BatteryCollector.health(7))
    }

    @Test fun power() {
        val r = PowerCollector.map(PowerSnapshot(screenOn = true, powerSave = false, idle = true))
        assertEquals(1.0, r.value("device.screen.on")!!, 0.0)
        assertEquals(0.0, r.value("device.power.save_mode")!!, 0.0)
        assertEquals(1.0, r.value("device.power.idle")!!, 0.0)
    }

    @Test fun boot() {
        val s = BootSnapshot(elapsedMs = 3_600_500, wallMs = 1_800_000_000_000, bootCount = 12)
        val r = BootCollector.map(s)
        assertEquals(3600.5, r.value("system.uptime")!!, 1e-9)
        assertEquals(12.0, r.value("device.boot.count")!!, 0.0)
        assertEquals(1_799_996_399.0, r.value("device.boot.time")!!, 0.0)
        assertNull(BootCollector.map(s.copy(bootCount = null)).value("device.boot.count"))
    }

    @Test fun newBoot() {
        val s = BootSnapshot(1000, 1_800_000_000_000, 12)
        assertTrue(BootCollector.isNewBoot(null, null, s))
        assertFalse(BootCollector.isNewBoot(12, s.bootTimeS, s))
        assertTrue(BootCollector.isNewBoot(11, s.bootTimeS, s))
        val noCount = s.copy(bootCount = null)
        assertFalse(BootCollector.isNewBoot(null, noCount.bootTimeS + 30, noCount))
        assertTrue(BootCollector.isNewBoot(null, noCount.bootTimeS - 3600, noCount))
    }

    @Test fun network() {
        val r = NetworkCollector.map(NetworkSnapshot(true, setOf("wifi", "vpn"), true, -61, 433))
        assertEquals(1.0, r.value("device.network.connected")!!, 0.0)
        assertEquals(setOf("wifi", "vpn"), r.current("device.network.type", "network.connection.type"))
        assertEquals(0.0, r.value("device.network.type", "network.connection.type" to "cell")!!, 0.0)
        assertEquals(1.0, r.value("device.wifi.connected")!!, 0.0)
        assertEquals(-61.0, r.value("device.wifi.rssi")!!, 0.0)
        assertEquals(433.0, r.value("device.wifi.link_speed")!!, 0.0)
        // Nothing that names the network.
        assertTrue(r.none { it.def.name.contains("ssid") })
    }

    @Test fun networkDown() {
        val r = NetworkCollector.map(NetworkSnapshot(false, emptySet(), false, null, null))
        assertEquals(0.0, r.value("device.network.connected")!!, 0.0)
        assertEquals(emptySet<String>(), r.current("device.network.type", "network.connection.type"))
        assertNull(r.value("device.wifi.rssi"))
    }

    @Test fun device() {
        val r = DeviceCollector.map(DeviceSnapshot(8_000, 3_000, false, 100_000, 40_000))
        assertEquals(8000.0, r.value("system.memory.limit")!!, 0.0)
        assertEquals(3000.0, r.value("system.memory.usage", "system.memory.state" to "free")!!, 0.0)
        assertEquals(5000.0, r.value("system.memory.usage", "system.memory.state" to "used")!!, 0.0)
        assertEquals(0.0, r.value("device.memory.low")!!, 0.0)
        val m = "system.filesystem.mountpoint" to "/data"
        assertEquals(100_000.0, r.value("system.filesystem.limit", m)!!, 0.0)
        assertEquals(40_000.0, r.value("system.filesystem.usage", m, "system.filesystem.state" to "free")!!, 0.0)
        assertEquals(60_000.0, r.value("system.filesystem.usage", m, "system.filesystem.state" to "used")!!, 0.0)
    }

    @Test fun thermal() {
        val r = ThermalCollector.map(ThermalSnapshot(2, 0.45f))
        assertEquals(2.0, r.value("device.thermal.status")!!, 0.0)
        assertEquals(0.45, r.value("device.thermal.headroom")!!, 1e-6)
        assertTrue(ThermalCollector.map(ThermalSnapshot(null, Float.NaN)).isEmpty())
    }

    @Test fun metricNamesAreUnique() {
        assertEquals(Metrics.ALL.size, Metrics.ALL.map { it.name }.toSet().size)
    }
}
