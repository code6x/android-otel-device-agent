package dev.code6x.otelagent.config

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

/** The local config file (imported by file, share sheet, QR code text or adb broadcast). */
class ConfigStore(context: Context) {
    private val file = File(context.filesDir, "config.json")

    /** The config in use. A broken local file is ignored (with a warning), so the app still runs. */
    fun load(): LoadedConfig {
        val local = if (file.exists()) file.readText() else null
        if (local != null) {
            try {
                return ConfigLoader.load(listOf(Source.LOCAL_FILE to local))
            } catch (e: ConfigException) {
                Log.e(TAG, "local config not used: ${e.message}")
                val d = ConfigLoader.load(emptyList())
                return d.copy(warnings = d.warnings + "local file not used: ${e.message}")
            }
        }
        return ConfigLoader.load(emptyList())
    }

    /** Checks the JSON and saves it as the local file. Throws [ConfigException]; nothing changes then. */
    fun import(json: String): LoadedConfig {
        val loaded = ConfigLoader.load(listOf(Source.LOCAL_FILE to json))
        val tmp = File(file.parentFile, "config.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) throw ConfigException("could not save the config file")
        Log.i(TAG, "local config imported (${loaded.warnings.size} warnings)")
        return loaded
    }

    fun reset() {
        file.delete()
        Log.i(TAG, "local config removed, defaults in use")
    }

    companion object {
        const val TAG = "OtelAgent"

        fun decodeBase64(text: String): String = String(Base64.decode(text.trim(), Base64.DEFAULT), Charsets.UTF_8)
    }
}
