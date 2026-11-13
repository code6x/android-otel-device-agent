package dev.code6x.otelagent

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.code6x.otelagent.config.ConfigException
import dev.code6x.otelagent.config.ConfigStore

/** The exact alarm of a waking timer. */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AgentService.instance?.onAlarm()
    }
}

/** Starts the service after a reboot or an app update, when the user had started it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AgentService.wanted(context)) return
        try {
            AgentService.start(context)
        } catch (e: IllegalStateException) {
            Log.e(AgentService.TAG, "could not start the service after ${intent.action}", e)
        }
    }
}

/**
 * Scripted setup: `adb shell am broadcast -a dev.code6x.otelagent.SET_CONFIG ...` (README).
 * Only senders with the DUMP permission get here (the adb shell has it; normal apps do not).
 * Extras: `json` (the config), or `json_b64` (the same, base64), or `reset` (true: back to the
 * defaults); `start` (true: start the service too).
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val store = ConfigStore(context)
        val msg = try {
            val json = intent.getStringExtra("json") ?: intent.getStringExtra("json_b64")?.let { ConfigStore.decodeBase64(it) }
            val text = when {
                intent.getBooleanExtra("reset", false) -> {
                    store.reset()
                    "ok: local config removed, defaults in use"
                }
                json != null -> {
                    val loaded = store.import(json)
                    "ok: config imported" + if (loaded.warnings.isEmpty()) "" else "; warnings: " + loaded.warnings.joinToString("; ")
                }
                else -> throw ConfigException("give the config as extra json or json_b64, or reset true")
            }
            val applied = AgentService.reload()
            var started = ""
            if (!applied && intent.getBooleanExtra("start", false)) {
                started = try {
                    AgentService.start(context)
                    "; service started"
                } catch (e: IllegalStateException) {
                    "; service NOT started (${e.javaClass.simpleName}): open the app and press Start"
                }
            }
            resultCode = Activity.RESULT_OK
            text + (if (applied) "; applied" else "") + started
        } catch (e: ConfigException) {
            resultCode = Activity.RESULT_CANCELED
            "error: ${e.message}"
        } catch (e: IllegalArgumentException) {
            resultCode = Activity.RESULT_CANCELED
            "error: ${e.message}"
        }
        resultData = msg
        Log.i(AgentService.TAG, "config broadcast: $msg")
    }

    companion object {
        const val ACTION = "dev.code6x.otelagent.SET_CONFIG"
    }
}
