package dev.code6x.otelagent.collect

/** One metric: name, unit (UCUM, as OpenTelemetry uses) and description. All are gauges. */
data class MetricDef(val name: String, val unit: String, val description: String)

/** One value read by a collector. */
data class Reading(val def: MetricDef, val value: Double, val attributes: Map<String, String> = emptyMap())

/** Every metric the app sends. The README has the same table. */
object Metrics {
    // battery
    val BATTERY_CHARGE = MetricDef("hw.battery.charge", "1", "Battery level, 0 to 1")
    val BATTERY_VOLTAGE = MetricDef("device.battery.voltage", "V", "Battery voltage")
    val BATTERY_TEMPERATURE = MetricDef("device.battery.temperature", "Cel", "Battery temperature")
    val BATTERY_CURRENT = MetricDef("device.battery.current", "A", "Battery current; positive is into the battery on most phones")
    val BATTERY_CHARGE_COUNTER = MetricDef("device.battery.charge_counter", "A.h", "Remaining battery charge")
    val BATTERY_CYCLES = MetricDef("device.battery.cycle_count", "{cycle}", "Battery charge cycles (Android 14+)")
    val BATTERY_STATUS = MetricDef("device.battery.status", "1", "1 for the current battery status, 0 for the others")
    val BATTERY_PLUG = MetricDef("device.battery.plug", "1", "1 for the current power source, 0 for the others")
    val BATTERY_HEALTH = MetricDef("device.battery.health", "1", "1 for the current battery health, 0 for the others")

    // power
    val SCREEN_ON = MetricDef("device.screen.on", "1", "1 when the screen is on")
    val POWER_SAVE = MetricDef("device.power.save_mode", "1", "1 when battery saver is on")
    val IDLE = MetricDef("device.power.idle", "1", "1 when the device is in idle (Doze) mode")

    // boot
    val UPTIME = MetricDef("system.uptime", "s", "Time since boot, deep sleep included")
    val BOOT_COUNT = MetricDef("device.boot.count", "{boot}", "Boots since the phone was set up")
    val BOOT_TIME = MetricDef("device.boot.time", "s", "Time of the last boot, Unix seconds")

    // network
    val NETWORK_CONNECTED = MetricDef("device.network.connected", "1", "1 when there is an active network")
    val NETWORK_TYPE = MetricDef("device.network.type", "1", "1 for each transport of the active network, 0 for the others")
    val WIFI_CONNECTED = MetricDef("device.wifi.connected", "1", "1 when Wi-Fi is connected")
    val WIFI_RSSI = MetricDef("device.wifi.rssi", "dBm", "Wi-Fi signal strength")
    val WIFI_LINK_SPEED = MetricDef("device.wifi.link_speed", "Mbit/s", "Wi-Fi link speed")

    // device
    val MEMORY_LIMIT = MetricDef("system.memory.limit", "By", "Total RAM")
    val MEMORY_USAGE = MetricDef("system.memory.usage", "By", "RAM by state: free (available to apps) and used")
    val MEMORY_LOW = MetricDef("device.memory.low", "1", "1 when the system is low on memory")
    val FS_LIMIT = MetricDef("system.filesystem.limit", "By", "Total size of the file system")
    val FS_USAGE = MetricDef("system.filesystem.usage", "By", "File system space by state: free and used")

    // thermal
    val THERMAL_STATUS = MetricDef("device.thermal.status", "1", "Thermal status: 0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown")
    val THERMAL_HEADROOM = MetricDef("device.thermal.headroom", "1", "Thermal headroom; 1.0 is where severe throttling starts")

    // the agent itself (sent with every reading)
    val AGENT_START_TIME = MetricDef("device.agent.start_time", "s", "Start time of the app process, Unix seconds; a new value is a new process")
    val AGENT_QUEUE_SIZE = MetricDef("device.agent.queue.size", "By", "Bytes waiting in the disk queue")
    val AGENT_QUEUE_DROPPED = MetricDef("device.agent.queue.dropped", "{file}", "Queue files dropped (size cap or max age) since the process started")
    val AGENT_WAKE = MetricDef("device.agent.wake", "1", "1 when the timer wakes the CPU for readings (wake_policy)")

    val ALL = listOf(
        BATTERY_CHARGE, BATTERY_VOLTAGE, BATTERY_TEMPERATURE, BATTERY_CURRENT, BATTERY_CHARGE_COUNTER,
        BATTERY_CYCLES, BATTERY_STATUS, BATTERY_PLUG, BATTERY_HEALTH,
        SCREEN_ON, POWER_SAVE, IDLE,
        UPTIME, BOOT_COUNT, BOOT_TIME,
        NETWORK_CONNECTED, NETWORK_TYPE, WIFI_CONNECTED, WIFI_RSSI, WIFI_LINK_SPEED,
        MEMORY_LIMIT, MEMORY_USAGE, MEMORY_LOW, FS_LIMIT, FS_USAGE,
        THERMAL_STATUS, THERMAL_HEADROOM,
        AGENT_START_TIME, AGENT_QUEUE_SIZE, AGENT_QUEUE_DROPPED, AGENT_WAKE,
    )
}

internal fun flag(b: Boolean) = if (b) 1.0 else 0.0

/** A state metric: one point per possible state, 1 for the current one, 0 for the others. */
internal fun states(def: MetricDef, attr: String, all: List<String>, current: Set<String>): List<Reading> =
    all.map { Reading(def, flag(it in current), mapOf(attr to it)) }
