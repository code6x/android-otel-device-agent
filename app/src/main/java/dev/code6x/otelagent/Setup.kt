package dev.code6x.otelagent

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.code6x.otelagent.config.WakePolicy

/** One line of the setup checklist. [done] is null when the app cannot read the setting. */
data class SetupItem(val title: String, val help: String, val done: Boolean?, val open: Intent?)

/** The setup checklist: the phone settings that keep the service alive and on time. */
object Setup {
    fun items(context: Context, wakePolicy: WakePolicy): List<SetupItem> {
        val pkg = context.packageName
        val appInfo = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val out = mutableListOf<SetupItem>()
        if (Build.VERSION.SDK_INT >= 33) {
            out += SetupItem(
                "Notifications allowed",
                "The service shows a quiet notification while it runs.",
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            )
        }
        val pm = context.getSystemService(PowerManager::class.java)
        out += SetupItem(
            "Battery optimisation off",
            "Lets the service run and send in the background.",
            pm.isIgnoringBatteryOptimizations(pkg),
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
        )
        if (Build.VERSION.SDK_INT >= 31 && wakePolicy != WakePolicy.NEVER) {
            val am = context.getSystemService(AlarmManager::class.java)
            out += SetupItem(
                "Alarms & reminders allowed",
                "Exact alarms wake the CPU for each reading (wake_policy). Without them the app holds a wake lock instead.",
                am.canScheduleExactAlarms(),
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg")),
            )
        }
        val maker = Build.MANUFACTURER.lowercase()
        if (maker in setOf("oneplus", "oppo", "realme")) {
            out += SetupItem(
                "OnePlus / OPPO: Allow background activity",
                "App info > Battery > Allow background activity. Without it the system freezes the app a few minutes after start, even with a foreground service.",
                null, appInfo,
            )
        }
        if (maker in setOf("xiaomi", "redmi", "poco")) {
            val autostart = Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            out += SetupItem(
                "Xiaomi: Autostart on",
                "Security > Autostart: turn it on for this app, or it is not started after a reboot and may be killed.",
                null,
                if (autostart.resolveActivity(context.packageManager) != null) autostart else appInfo,
            )
            out += SetupItem(
                "Xiaomi: Battery saver \"No restrictions\"",
                "App info > Battery saver > No restrictions.",
                null, appInfo,
            )
        }
        out += SetupItem(
            "VPN with split tunneling: include this app",
            "If a VPN sends only some apps through it, include this app, or the receiver may not be reachable. The queue keeps the data meanwhile.",
            null, Intent(Settings.ACTION_VPN_SETTINGS),
        )
        return out
    }
}
