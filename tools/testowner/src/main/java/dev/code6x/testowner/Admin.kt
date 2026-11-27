package dev.code6x.testowner

import android.app.admin.DevicePolicyManager
import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

class Admin : DeviceAdminReceiver()

/**
 * `adb shell am broadcast -a dev.code6x.testowner.SET -n dev.code6x.testowner/.SetRestrictions
 *  --es wake_policy always --ei interval 30` sets these as the agent's managed configurations
 * (all of them: keys not given are removed). No extras: all removed.
 */
class SetRestrictions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val b = Bundle(intent.extras ?: Bundle())
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        dpm.setApplicationRestrictions(ComponentName(context, Admin::class.java), AGENT, b)
        @Suppress("DEPRECATION")
        val msg = "set for $AGENT: " + b.keySet().joinToString { "$it=${b.get(it)}" }
        Log.i("TestOwner", msg)
        resultData = msg
    }

    companion object {
        const val AGENT = "dev.code6x.otelagent"
    }
}
