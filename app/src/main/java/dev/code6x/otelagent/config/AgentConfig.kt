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

data class AgentConfig(
    val exporter: ExporterConfig,
    val deviceId: String,
    val attributes: Map<String, String>,
    val collectors: Map<String, CollectorConfig>,
    val buffer: BufferConfig,
    val wakePolicy: WakePolicy,
) {
    fun collector(name: String): CollectorConfig =
        collectors[name] ?: CollectorConfig(enabled = false, intervalSeconds = DEFAULT_INTERVAL)

    companion object {
        const val DEFAULT_INTERVAL = 60
        val COLLECTORS = listOf("battery", "power", "boot", "network", "device", "thermal")
    }
}

class ConfigException(message: String) : Exception(message)
