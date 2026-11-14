package dev.code6x.otelagent.config

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Where a config value came from. Later sources win. */
enum class Source(val label: String) {
    DEFAULT("default"),
    LOCAL_FILE("local file"),
}

/** One value of the merged config, with its source. The path is the list of JSON keys. */
data class ConfigEntry(val path: List<String>, val value: Any, val source: Source) {
    val key: String get() = path.joinToString(" > ")
}

/** The config in use: the typed config, every value with its source, and warnings. */
data class LoadedConfig(
    val config: AgentConfig,
    val entries: List<ConfigEntry>,
    val warnings: List<String>,
)

/**
 * Reads config documents (JSON), merges them over the built-in defaults and builds the
 * typed [AgentConfig]. The same document shape is meant for managed configurations and
 * OpAMP later (#3): each source is one more layer.
 */
object ConfigLoader {
    val DEFAULTS_JSON = """
        {
          "exporter": {
            "endpoint": "",
            "logs_endpoint": "",
            "protocol": "http/protobuf",
            "headers": {},
            "timeout": 10
          },
          "resource": {
            "device.id": "",
            "attributes": {}
          },
          "collectors": {
            "battery": {"enabled": true, "interval": 60},
            "power": {"enabled": true, "interval": 60},
            "boot": {"enabled": true, "interval": 60},
            "network": {"enabled": true, "interval": 60},
            "device": {"enabled": true, "interval": 60},
            "thermal": {"enabled": true, "interval": 60}
          },
          "buffer": {
            "max_bytes": 20971520,
            "max_age": 604800
          },
          "wake_policy": "when_charging"
        }
    """.trimIndent()

    /** Objects whose keys are free (any name, text values). */
    private val FREE_MAPS = setOf(listOf("exporter", "headers"), listOf("resource", "attributes"))

    private val defaultsLayer: Map<List<String>, Any> by lazy { flatten(DEFAULTS_JSON) }

    const val MIN_INTERVAL = 10
    const val MAX_INTERVAL = 86400

    /** Turns one JSON document into leaf values by path. Throws [ConfigException]. */
    fun flatten(json: String): Map<List<String>, Any> {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw ConfigException("not valid JSON: ${e.message}")
        }
        val out = LinkedHashMap<List<String>, Any>()
        walk(root, emptyList(), out)
        return out
    }

    private fun walk(obj: JSONObject, path: List<String>, out: MutableMap<List<String>, Any>) {
        for (k in obj.keys()) {
            val p = path + k
            when (val v = obj.get(k)) {
                is JSONObject -> walk(v, p, out)
                is JSONArray -> throw ConfigException("${p.joinToString(" > ")}: a list is not allowed here")
                JSONObject.NULL -> {}
                else -> out[p] = v
            }
        }
    }

    /** Merges the layers over the defaults (later layers win) and builds the config. */
    fun load(layers: List<Pair<Source, String>>): LoadedConfig {
        val merged = LinkedHashMap<List<String>, ConfigEntry>()
        defaultsLayer.forEach { (p, v) -> merged[p] = ConfigEntry(p, v, Source.DEFAULT) }
        val warnings = mutableListOf<String>()
        for ((source, json) in layers) {
            for ((p, v) in flatten(json)) {
                if (!known(p)) {
                    warnings += "${p.joinToString(" > ")} (${source.label}): unknown key, not used"
                    continue
                }
                merged[p] = ConfigEntry(p, v, source)
            }
        }
        val config = build(merged.mapValues { it.value.value })
        return LoadedConfig(config, merged.values.toList(), warnings)
    }

    private fun known(p: List<String>): Boolean {
        if (FREE_MAPS.any { p.size == it.size + 1 && p.subList(0, it.size) == it }) return true
        if (p.size == 3 && p[0] == "collectors") return p[1] in AgentConfig.COLLECTORS && p[2] in setOf("enabled", "interval")
        return p in defaultsLayer.keys
    }

    private fun build(m: Map<List<String>, Any>): AgentConfig {
        fun name(p: List<String>) = p.joinToString(" > ")
        fun str(vararg p: String): String = when (val v = m[p.toList()]) {
            is String -> v.trim()
            null -> ""
            else -> throw ConfigException("${name(p.toList())}: must be text")
        }
        fun long(vararg p: String, min: Long, max: Long): Long {
            val v = m[p.toList()]
            val n = (v as? Number) ?: throw ConfigException("${name(p.toList())}: must be a number")
            if (n.toDouble() != Math.floor(n.toDouble())) throw ConfigException("${name(p.toList())}: must be a whole number")
            val l = n.toLong()
            if (l < min || l > max) throw ConfigException("${name(p.toList())}: must be from $min to $max")
            return l
        }
        fun bool(vararg p: String): Boolean =
            m[p.toList()] as? Boolean ?: throw ConfigException("${name(p.toList())}: must be true or false")
        fun freeMap(prefix: List<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((p, v) in m) {
                if (p.size == prefix.size + 1 && p.subList(0, prefix.size) == prefix) {
                    out[p.last()] = v as? String ?: throw ConfigException("${name(p)}: must be text")
                }
            }
            return out
        }

        val endpoint = str("exporter", "endpoint")
        val logsEndpoint = str("exporter", "logs_endpoint")
        for ((key, url) in listOf("endpoint" to endpoint, "logs_endpoint" to logsEndpoint)) {
            if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                throw ConfigException("exporter > $key: must start with http:// or https://")
            }
        }
        val protocol = str("exporter", "protocol")
        if (protocol != "http/protobuf") throw ConfigException("exporter > protocol: only http/protobuf is supported")
        val wakeKey = str("wake_policy")
        val wake = WakePolicy.parse(wakeKey)
            ?: throw ConfigException("wake_policy: must be one of ${WakePolicy.entries.joinToString { it.key }}")
        val collectors = AgentConfig.COLLECTORS.associateWith { c ->
            CollectorConfig(
                enabled = bool("collectors", c, "enabled"),
                intervalSeconds = long("collectors", c, "interval", min = MIN_INTERVAL.toLong(), max = MAX_INTERVAL.toLong()).toInt(),
            )
        }
        return AgentConfig(
            exporter = ExporterConfig(
                endpoint = endpoint,
                logsEndpoint = logsEndpoint,
                protocol = protocol,
                headers = freeMap(listOf("exporter", "headers")),
                timeoutSeconds = long("exporter", "timeout", min = 1, max = 300).toInt(),
            ),
            deviceId = str("resource", "device.id"),
            attributes = freeMap(listOf("resource", "attributes")),
            collectors = collectors,
            buffer = BufferConfig(
                maxBytes = long("buffer", "max_bytes", min = 64L * 1024, max = 1024L * 1024 * 1024),
                maxAgeSeconds = long("buffer", "max_age", min = 60, max = 90L * 86400),
            ),
            wakePolicy = wake,
        )
    }

    /** Text for the screen: header values are hidden, as they often hold a token. */
    fun display(entry: ConfigEntry): String {
        val hidden = entry.path.size == 3 && entry.path[0] == "exporter" && entry.path[1] == "headers"
        val value = if (hidden) "(hidden)" else if (entry.value is String) "\"${entry.value}\"" else entry.value.toString()
        return "${entry.key} = $value  [${entry.source.label}]"
    }
}
