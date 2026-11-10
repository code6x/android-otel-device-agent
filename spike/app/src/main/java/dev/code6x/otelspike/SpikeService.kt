package dev.code6x.otelspike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * A foreground service that reads the battery on a fixed interval (60 s by default).
 * No wake lock: the spike tests what HyperOS does to a plain foreground service.
 * Each sample is also written to files/samples.csv, the evidence for the 24 h test.
 *
 * Settings come as intent extras: otlp (base URL), opamp (URL), interval (s), device_id.
 */
class SpikeService : Service() {
    private val tag = "OtelSpike"
    private lateinit var settings: Settings
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var opamp: Opamp? = null
    private var started = false
    private var lastTickElapsed = 0L

    private val tick = object : Runnable {
        override fun run() {
            try {
                sample()
            } catch (e: Exception) {
                Log.e(tag, "sample failed", e)
            }
            handler.postDelayed(this, settings.intervalSeconds * 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        thread = HandlerThread("spike-tick").apply { start() }
        handler = Handler(thread.looper)
        Log.i(tag, "service created, pid=${Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        intent?.getStringExtra("otlp")?.let { settings.otlpEndpoint = it }
        intent?.getStringExtra("opamp")?.let { settings.opampEndpoint = it }
        intent?.getStringExtra("device_id")?.let { settings.deviceId = it }
        if (intent?.hasExtra("interval") == true) {
            settings.intervalSeconds = intent.getIntExtra("interval", 60)
        }
        if (!settings.configured) {
            Log.w(tag, "no otlp endpoint set, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            Telemetry.start(application, settings)
            opamp = Opamp(settings) { reschedule() }.also { it.start() }
            handler.post(tick)
            Log.i(tag, "sampling every ${settings.intervalSeconds} s")
        }
        return START_STICKY
    }

    private fun reschedule() {
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, settings.intervalSeconds * 1000L)
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val gap = if (lastTickElapsed == 0L) 0 else (elapsed - lastTickElapsed) / 1000
        lastTickElapsed = elapsed
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = b?.let { 100.0 * it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) /
            it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: -1.0
        val voltage = (b?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1) / 1000.0
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1) / 10.0
        val plugged = when (b?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            0 -> "none"
            else -> "other"
        }
        val screen = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        Telemetry.library.record(level, plugged, voltage, temp, screen, settings.intervalSeconds)
        Telemetry.own.record(level, plugged, voltage, temp, screen, settings.intervalSeconds)
        Telemetry.flush()
        File(filesDir, "samples.csv").appendText(
            "$now,$elapsed,$gap,$level,$plugged,${if (screen) 1 else 0},${settings.intervalSeconds},${Process.myPid()}\n",
        )
        Log.i(tag, "tick level=$level plugged=$plugged screen=$screen gap=${gap}s interval=${settings.intervalSeconds}s")
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("spike", "Telemetry", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, "spike")
            .setContentTitle("OTel spike")
            .setContentText("Reading the battery")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Log.w(tag, "service destroyed")
        handler.removeCallbacks(tick)
        opamp?.stop()
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
