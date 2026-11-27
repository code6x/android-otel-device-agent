package dev.code6x.otelagent.config

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * The config layers on the phone: the local file (imported by file, share sheet, QR code text
 * or adb broadcast), the managed configurations (set by a device admin) and the last good
 * OpAMP config (kept, so a restart or an empty offer from the server does not lose it).
 */
class ConfigStore(private val context: Context) {
    private val file = File(context.filesDir, "config.json")
    /** The last good OpAMP config: `{"hash": "...", "body": "..."}`, one file so both change together. */
    private val opampFile = File(context.filesDir, "opamp.json")

    private fun opampSaved(): JSONObject? = try {
        if (opampFile.exists()) JSONObject(opampFile.readText()) else null
    } catch (e: JSONException) {
        Log.e(TAG, "saved OpAMP config not readable: ${e.message}")
        null
    }

    /** The config in use. A layer that does not work is left out (with a warning), so the app still runs. */
    fun load(): LoadedConfig {
        val warnings = mutableListOf<String>()
        val opamp = opampSaved()?.optString("body")
        val loaded = Layers.merge(baseLayers(warnings), opamp)
        loaded.warnings.forEach { Log.w(TAG, it) }
        return loaded.copy(warnings = warnings + loaded.warnings)
    }

    /** The layers under OpAMP: the local file and the managed configurations. */
    fun baseLayers(warnings: MutableList<String> = mutableListOf()): List<Pair<Source, String>> {
        val out = mutableListOf<Pair<Source, String>>()
        if (file.exists()) out += Source.LOCAL_FILE to file.readText()
        try {
            Layers.managedToJson(managed())?.let { out += Source.MANAGED to it }
        } catch (e: ConfigException) {
            warnings += "managed config not used: ${e.message}"
        }
        return out
    }

    private fun managed(): Map<String, Any?> {
        val rm = context.getSystemService(RestrictionsManager::class.java) ?: return emptyMap()
        val b: Bundle = rm.applicationRestrictions ?: return emptyMap()
        @Suppress("DEPRECATION")
        return b.keySet().associateWith { b.get(it) }
    }

    /** Checks the JSON and saves it as the local file. Throws [ConfigException]; nothing changes then. */
    fun import(json: String): LoadedConfig {
        val loaded = ConfigLoader.load(listOf(Source.LOCAL_FILE to json))
        write(file, json)
        Log.i(TAG, "local config imported (${loaded.warnings.size} warnings)")
        return loaded
    }

    fun reset() {
        file.delete()
        Log.i(TAG, "local config removed")
    }

    /** Hash of the last OpAMP config that was applied (hex), or empty. */
    fun opampHash(): String = opampSaved()?.optString("hash").orEmpty()

    /** Keeps an applied OpAMP config and its hash. */
    fun saveOpamp(body: String, hashHex: String) {
        write(opampFile, JSONObject().put("hash", hashHex).put("body", body).toString())
    }

    private fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) throw ConfigException("could not save ${target.name}")
    }

    companion object {
        const val TAG = "OtelAgent"

        fun decodeBase64(text: String): String = String(Base64.decode(text.trim(), Base64.DEFAULT), Charsets.UTF_8)
    }
}
