package dev.code6x.otelagent.config

import org.json.JSONException
import org.json.JSONObject

/** What to do with a config document from the OpAMP server. */
sealed class RemoteResult {
    /** Use it: [loaded] is the new merged config. */
    data class Applied(val loaded: LoadedConfig) : RemoteResult()

    /** Do not use it; the last good config stays. [reason] goes back to the server. */
    data class Refused(val reason: String) : RemoteResult()

    /** Empty: the server has no config for us. The last good config stays. */
    data object Kept : RemoteResult()
}

/**
 * The order of the config layers, later wins: defaults, local file, managed configurations,
 * OpAMP. A layer that does not work is left out with a warning, so the app still runs.
 */
object Layers {
    /** Merges [base] (local file, managed) and the last good OpAMP document (if any). */
    fun merge(base: List<Pair<Source, String>>, opamp: String?): LoadedConfig {
        val warnings = mutableListOf<String>()
        val used = usable(base, warnings)
        var loaded = ConfigLoader.load(used)
        // OpAMP turned off (by the local file or managed configurations): its layer is not used.
        if (opamp != null && loaded.config.opamp.on) {
            when (val r = checkRemote(used, opamp)) {
                is RemoteResult.Applied -> loaded = r.loaded
                is RemoteResult.Refused -> warnings += "OpAMP config not used: ${r.reason}"
                RemoteResult.Kept -> {}
            }
        }
        return loaded.copy(warnings = warnings + loaded.warnings)
    }

    /** The [base] layers that work, in order; a layer that does not load is left out. */
    fun usable(base: List<Pair<Source, String>>, warnings: MutableList<String> = mutableListOf()): List<Pair<Source, String>> {
        val used = mutableListOf<Pair<Source, String>>()
        for (layer in base) {
            try {
                ConfigLoader.load(used + layer)
                used += layer
            } catch (e: ConfigException) {
                warnings += "${layer.first.label} config not used: ${e.message}"
            }
        }
        return used
    }

    /**
     * Checks a config document from the OpAMP server over the [base] layers. Empty: kept (the
     * server offers an empty config until one is set, also after a reconnect or restart).
     * A document that does not parse, or leaves no exporter endpoint, is refused.
     */
    fun checkRemote(base: List<Pair<Source, String>>, body: String): RemoteResult {
        if (body.isBlank()) return RemoteResult.Kept
        val loaded = try {
            ConfigLoader.load(base + (Source.OPAMP to body))
        } catch (e: ConfigException) {
            return RemoteResult.Refused(e.message ?: "not valid")
        }
        if (loaded.config.exporter.endpoint.isEmpty()) return RemoteResult.Refused("no exporter endpoint")
        return RemoteResult.Applied(loaded)
    }

    /** Keys of `app_restrictions.xml`, with the config key each one sets. */
    const val KEY_CONFIG_JSON = "config_json"
    const val KEY_ENDPOINT = "exporter_endpoint"
    const val KEY_INTERVAL = "interval"
    const val KEY_WAKE_POLICY = "wake_policy"
    const val KEY_OPAMP_ENABLED = "opamp_enabled"
    const val KEY_OPAMP_ENDPOINT = "opamp_endpoint"

    /**
     * Turns managed configurations (the app restrictions, as a key-value map) into one config
     * document. `config_json` is a whole document; the other keys set one value on top of it.
     * Empty text and 0 mean "not set" (some admin tools send every key). Null: nothing set.
     */
    fun managedToJson(r: Map<String, Any?>): String? {
        val doc = when (val j = r[KEY_CONFIG_JSON]) {
            is String -> if (j.isBlank()) JSONObject() else try {
                JSONObject(j)
            } catch (e: JSONException) {
                throw ConfigException("$KEY_CONFIG_JSON: not valid JSON: ${e.message}")
            }
            else -> JSONObject()
        }
        fun obj(parent: JSONObject, key: String): JSONObject =
            parent.optJSONObject(key) ?: JSONObject().also { parent.put(key, it) }
        fun text(key: String): String? = (r[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        text(KEY_ENDPOINT)?.let { obj(doc, "exporter").put("endpoint", it) }
        (r[KEY_INTERVAL] as? Int)?.takeIf { it != 0 }?.let { n ->
            val cs = obj(doc, "collectors")
            AgentConfig.COLLECTORS.forEach { obj(cs, it).put("interval", n) }
        }
        text(KEY_WAKE_POLICY)?.let { doc.put("wake_policy", it) }
        text(KEY_OPAMP_ENABLED)?.let {
            val on = it.toBooleanStrictOrNull() ?: throw ConfigException("$KEY_OPAMP_ENABLED: must be true or false")
            obj(doc, "opamp").put("enabled", on)
        }
        text(KEY_OPAMP_ENDPOINT)?.let { obj(doc, "opamp").put("endpoint", it) }
        return if (doc.length() == 0) null else doc.toString()
    }
}
