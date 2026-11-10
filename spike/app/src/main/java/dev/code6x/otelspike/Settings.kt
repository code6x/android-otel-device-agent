package dev.code6x.otelspike

import android.content.Context

/**
 * The spike's settings. They are set over adb with intent extras (see docs/spike.md),
 * so no address of a receiver is ever in the code.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("spike", Context.MODE_PRIVATE)

    var otlpEndpoint: String
        get() = prefs.getString("otlp", "") ?: ""
        set(v) = prefs.edit().putString("otlp", v).apply()

    var opampEndpoint: String
        get() = prefs.getString("opamp", "") ?: ""
        set(v) = prefs.edit().putString("opamp", v).apply()

    var intervalSeconds: Int
        get() = prefs.getInt("interval", 60)
        set(v) = prefs.edit().putInt("interval", v).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "spike-device-1") ?: "spike-device-1"
        set(v) = prefs.edit().putString("device_id", v).apply()

    /** Kept across restarts so the OpAMP server sees the same agent. */
    var instanceUid: String
        get() = prefs.getString("instance_uid", "") ?: ""
        set(v) = prefs.edit().putString("instance_uid", v).apply()

    /** Hash of the last remote config handled, so a config offered again is not applied again. */
    var lastConfigHash: String
        get() = prefs.getString("last_config_hash", "") ?: ""
        set(v) = prefs.edit().putString("last_config_hash", v).apply()

    val configured: Boolean get() = otlpEndpoint.isNotEmpty()
}
