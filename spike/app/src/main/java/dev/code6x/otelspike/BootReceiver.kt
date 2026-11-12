package dev.code6x.otelspike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service again after a reboot, with the settings it saved before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings(context).configured) return
        context.startForegroundService(Intent(context, SpikeService::class.java))
    }
}
