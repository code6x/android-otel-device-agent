package dev.code6x.otelagent.config

/** When the timer may wake the CPU for a reading. See the README for what each value costs. */
enum class WakePolicy(val key: String) {
    NEVER("never"),
    WHEN_CHARGING("when_charging"),
    ALWAYS("always"),
    ;

    /** True when this policy wakes the CPU, given whether the phone is on a charger. */
    fun wakes(charging: Boolean): Boolean = when (this) {
        NEVER -> false
        WHEN_CHARGING -> charging
        ALWAYS -> true
    }

    companion object {
        fun parse(key: String): WakePolicy? = entries.firstOrNull { it.key == key }
    }
}

/** Unit of the raw BATTERY_PROPERTY_CURRENT_NOW value. Android says µA; some phones give mA. */
enum class CurrentUnit(val key: String) {
    AUTO("auto"),
    MICRO_A("uA"),
    MILLI_A("mA"),
    ;

    companion object {
        fun parse(key: String): CurrentUnit? = entries.firstOrNull { it.key == key }
    }
}

data class ExporterConfig(
    /** Full URL of the OTLP/HTTP metrics endpoint. Empty: data stays in the queue. */
    val endpoint: String,
    /** Full URL of the OTLP/HTTP logs endpoint (boot records). Empty: no log records. */
    val logsEndpoint: String,
    val protocol: String,
    val headers: Map<String, String>,
    val timeoutSeconds: Int,
)

data class CollectorConfig(val enabled: Boolean, val intervalSeconds: Int)

data class BufferConfig(val maxBytes: Long, val maxAgeSeconds: Long)

/** The OpAMP server. The client runs only when enabled and the endpoint is set. */
data class OpampConfig(val enabled: Boolean, val endpoint: String) {
    val on: Boolean get() = enabled && endpoint.isNotEmpty()
}

data class AgentConfig(
    val exporter: ExporterConfig,
    val deviceId: String,
    val attributes: Map<String, String>,
    val collectors: Map<String, CollectorConfig>,
    val buffer: BufferConfig,
    val wakePolicy: WakePolicy,
    val batteryCurrentUnit: CurrentUnit = CurrentUnit.AUTO,
    /** `collectors > apps > packages`: the packages the apps collector looks for. */
    val appPackages: List<String> = emptyList(),
    val opamp: OpampConfig = OpampConfig(enabled = true, endpoint = ""),
) {
    fun collector(name: String): CollectorConfig =
        collectors[name] ?: CollectorConfig(enabled = false, intervalSeconds = DEFAULT_INTERVAL)

    companion object {
        const val DEFAULT_INTERVAL = 60
        /** Collectors that run shell commands through Shizuku. All are off by default. */
        val SHIZUKU_COLLECTORS = listOf("apps", "boot_reason", "batterystats", "thermal_sensors")
        val COLLECTORS = listOf("battery", "power", "boot", "network", "device", "thermal") + SHIZUKU_COLLECTORS
    }
}

class ConfigException(message: String) : Exception(message)
