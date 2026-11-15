package dev.code6x.otelagent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.code6x.otelagent.collect.BatteryCollector
import dev.code6x.otelagent.collect.BootCollector
import dev.code6x.otelagent.collect.Collector
import dev.code6x.otelagent.collect.Collectors
import dev.code6x.otelagent.collect.Metrics
import dev.code6x.otelagent.collect.Reading
import dev.code6x.otelagent.config.ConfigStore
import dev.code6x.otelagent.config.LoadedConfig
import dev.code6x.otelagent.export.FileQueue
import dev.code6x.otelagent.export.OtlpEncoder
import dev.code6x.otelagent.export.QueueSender
import dev.code6x.otelagent.export.Target
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The one foreground service. A timer reads the due collectors, writes one OTLP body to the
 * disk queue and wakes the sender. Whether the timer wakes the CPU is `wake_policy`:
 * - waking: an exact alarm (setExactAndAllowWhileIdle) and a short wake lock for each tick;
 *   without the exact-alarm permission, a partial wake lock held between ticks instead.
 * - not waking: a Handler timer; it stops while the CPU sleeps, so readings may come late.
 *   No alarm and no wake lock then.
 */
class AgentService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sendExecutor: ScheduledExecutorService
    private lateinit var store: ConfigStore
    private lateinit var loaded: LoadedConfig
    private lateinit var encoder: OtlpEncoder
    private lateinit var metricQueue: FileQueue
    private lateinit var logQueue: FileQueue
    private lateinit var metricSender: QueueSender
    private lateinit var logSender: QueueSender
    private lateinit var collectors: List<Collector>
    private lateinit var tickLock: PowerManager.WakeLock
    private lateinit var holdLock: PowerManager.WakeLock
    private lateinit var alarmIntent: PendingIntent
    private val plan = TickPlan()

    /** Set in onDestroy; a tick still running then must not set an alarm or take a lock. */
    @Volatile private var destroyed = false

    private val tickRunnable = Runnable { tick() }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val charging = intent.action == Intent.ACTION_POWER_CONNECTED
            handler.post { reschedule(charging) }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = sendNow(null)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        thread = HandlerThread("agent-tick").apply { start() }
        handler = Handler(thread.looper)
        sendExecutor = Executors.newSingleThreadScheduledExecutor()
        val pm = getSystemService(PowerManager::class.java)
        tickLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "otelagent:tick").apply { setReferenceCounted(false) }
        holdLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "otelagent:hold").apply { setReferenceCounted(false) }
        alarmIntent = PendingIntent.getBroadcast(
            this, 0, Intent(this, TickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        store = ConfigStore(this)
        collectors = Collectors.create(this)
        val queueRoot = File(filesDir, "queue")
        metricQueue = FileQueue(File(queueRoot, "metrics"), 0, 0)
        logQueue = FileQueue(File(queueRoot, "logs"), 0, 0)
        metricSender = QueueSender(metricQueue)
        logSender = QueueSender(logQueue)
        applyConfig(store.load())

        registerReceiver(powerReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        try {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "no network callback: ${e.message}")
        }
        sendExecutor.scheduleWithFixedDelay({ drainAll() }, 30, 30, TimeUnit.SECONDS)
        handler.post { checkBoot() }
        handler.post(tickRunnable)
        Log.i(TAG, "service created, pid=${android.os.Process.myPid()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        if (intent?.action == ACTION_RELOAD) reload()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.w(TAG, "service destroyed")
        destroyed = true
        instance = null
        cancelAlarm()
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(powerReceiver)
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: RuntimeException) {
        }
        sendExecutor.shutdown()
        thread.quitSafely()
        synchronized(this) {
            // A schedule() that ran before `destroyed` was set may have set these again.
            cancelAlarm()
            if (tickLock.isHeld) tickLock.release()
            if (holdLock.isHeld) holdLock.release()
        }
        AgentState.timerMode = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Read the config file again and use it from the next tick (which is now). */
    fun reload() {
        handler.post {
            applyConfig(store.load())
            tick()
        }
    }

    private fun applyConfig(c: LoadedConfig) {
        loaded = c
        val cfg = c.config
        encoder = OtlpEncoder(
            OtlpEncoder.resource(
                BuildConfig.VERSION_NAME, Build.MODEL, Build.MANUFACTURER, Build.VERSION.RELEASE,
                cfg.deviceId, cfg.attributes,
            ),
            BuildConfig.VERSION_NAME,
        )
        for (q in listOf(metricQueue, logQueue)) {
            q.maxBytes = cfg.buffer.maxBytes
            q.maxAgeMs = cfg.buffer.maxAgeSeconds * 1000
        }
        plan.reset()
        Log.i(TAG, "config: endpoint ${if (cfg.exporter.endpoint.isEmpty()) "not set" else "set"}, " +
            "wake_policy=${cfg.wakePolicy.key}, collectors on: " +
            cfg.collectors.filter { it.value.enabled }.keys.joinToString())
    }

    private fun intervals(): Map<String, Int> =
        loaded.config.collectors.filter { it.value.enabled }.mapValues { it.value.intervalSeconds }

    private fun tick() {
        handler.removeCallbacks(tickRunnable)
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val charging = BatteryCollector.isCharging(this)
        val wake = loaded.config.wakePolicy.wakes(charging)
        val due = plan.due(now, intervals())
        if (due.isNotEmpty()) {
            val readings = mutableListOf<Reading>()
            for (c in collectors.filter { it.name in due }) {
                try {
                    val r = c.collect()
                    readings += r
                    AgentState.collectors[c.name] = AgentState.CollectorState(wall, r, null)
                } catch (e: Exception) {
                    Log.e(TAG, "collector ${c.name} failed", e)
                    AgentState.collectors[c.name] = AgentState.CollectorState(wall, emptyList(), e.toString())
                }
            }
            readings += Reading(Metrics.AGENT_START_TIME, (AgentState.processStartMs / 1000).toDouble())
            readings += Reading(Metrics.AGENT_QUEUE_SIZE, metricQueue.sizeBytes().toDouble())
            readings += Reading(Metrics.AGENT_QUEUE_DROPPED, (metricQueue.dropped + logQueue.dropped).toDouble())
            readings += Reading(Metrics.AGENT_WAKE, if (wake) 1.0 else 0.0)
            try {
                metricQueue.add(encoder.metrics(readings, wall))
            } catch (e: Exception) {
                Log.e(TAG, "queue write failed", e)
            }
            AgentState.lastReadingMs = wall
            Log.i(TAG, "tick: read ${due.joinToString()}; charging=$charging wake=$wake")
        }
        // Keep the CPU awake (if this tick came by alarm) until the send is done.
        sendNow(if (tickLock.isHeld) ({ if (tickLock.isHeld) tickLock.release() }) else null)
        schedule(plan.nextDelay(now, intervals()), wake)
    }

    private fun reschedule(charging: Boolean) {
        val now = SystemClock.elapsedRealtime()
        schedule(plan.nextDelay(now, intervals()), loaded.config.wakePolicy.wakes(charging))
    }

    @Synchronized
    private fun schedule(delay: Long?, wake: Boolean) {
        if (destroyed) return
        cancelAlarm()
        handler.removeCallbacks(tickRunnable)
        if (delay == null) {
            if (holdLock.isHeld) holdLock.release()
            AgentState.timerMode = "no collector on"
            return
        }
        if (wake && canExact()) {
            if (holdLock.isHeld) holdLock.release()
            val am = getSystemService(AlarmManager::class.java)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, alarmIntent)
                // Backup, in case the alarm does not come: runs only while the CPU is awake.
                handler.postDelayed(tickRunnable, delay + 30_000)
                AgentState.timerMode = "wakes the CPU (exact alarm)"
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "exact alarm not allowed: ${e.message}")
            }
        }
        if (wake) {
            // No exact alarms allowed: keep the CPU awake between ticks instead.
            holdLock.acquire(delay + 60_000)
            AgentState.timerMode = "wakes the CPU (wake lock; exact alarms not allowed)"
        } else {
            if (holdLock.isHeld) holdLock.release()
            AgentState.timerMode = "does not wake the CPU (timer)"
        }
        handler.postDelayed(tickRunnable, delay)
    }

    private fun canExact(): Boolean =
        Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun cancelAlarm() {
        getSystemService(AlarmManager::class.java).cancel(alarmIntent)
    }

    /** Called by [TickReceiver] when the exact alarm fires; the system holds a wake lock until we take ours. */
    fun onAlarm() {
        if (destroyed) return
        tickLock.acquire(60_000)
        handler.post(tickRunnable)
    }

    private fun checkBoot() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val now = BootCollector.read(this)
        val lastCount = if (prefs.contains("boot_count")) prefs.getInt("boot_count", 0) else null
        val lastTime = if (prefs.contains("boot_time")) prefs.getLong("boot_time", 0) else null
        if (!BootCollector.isNewBoot(lastCount, lastTime, now)) return
        val cfg = loaded.config
        if (cfg.collectors["boot"]?.enabled == true && cfg.exporter.logsEndpoint.isNotEmpty()) {
            val attrs = mutableMapOf("device.boot.time" to now.bootTimeS.toString())
            now.bootCount?.let { attrs["device.boot.count"] = it.toString() }
            try {
                logQueue.add(encoder.log("device.boot", "device boot", attrs, now.bootTimeS * 1000, System.currentTimeMillis()))
                Log.i(TAG, "boot record queued (boot count ${now.bootCount})")
            } catch (e: Exception) {
                Log.e(TAG, "boot record not queued", e)
            }
        }
        prefs.edit().apply {
            if (now.bootCount != null) putInt("boot_count", now.bootCount) else remove("boot_count")
            putLong("boot_time", now.bootTimeS)
        }.apply()
    }

    private fun target(url: String): Target? =
        if (url.isEmpty()) null else Target(url, loaded.config.exporter.headers, loaded.config.exporter.timeoutSeconds)

    private fun sendNow(after: (() -> Unit)?) {
        try {
            sendExecutor.execute {
                try {
                    drainAll()
                } finally {
                    after?.invoke()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            after?.invoke()
        }
    }

    private fun drainAll() {
        try {
            val cfg = loaded.config
            val r = metricSender.drain(target(cfg.exporter.endpoint))
            if (r.sent > 0 || r.dropped > 0 || r.error != null) {
                AgentState.lastExportMs = System.currentTimeMillis()
                AgentState.lastExportResult = if (r.error == null) "ok, sent ${r.sent}" else "sent ${r.sent}, then: ${r.error}"
                Log.i(TAG, "send: ${AgentState.lastExportResult}, ${r.left} files wait")
            }
            if (cfg.exporter.logsEndpoint.isNotEmpty()) {
                logSender.drain(target(cfg.exporter.logsEndpoint))
            }
            AgentState.queueFiles = metricQueue.files().size
            AgentState.queueBytes = metricQueue.sizeBytes()
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
        }
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val TAG = "OtelAgent"
        const val PREFS = "agent"
        const val CHANNEL = "agent"
        const val NOTIFICATION_ID = 1
        const val ACTION_RELOAD = "dev.code6x.otelagent.RELOAD"

        @Volatile var instance: AgentService? = null
            private set

        fun wanted(context: Context) = context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("enabled", false)

        /** Starts the service and remembers that it should run (also after a reboot). */
        fun start(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            context.startForegroundService(Intent(context, AgentService::class.java))
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
            context.stopService(Intent(context, AgentService::class.java))
        }

        /** Applies a new config: at once when the service runs. Returns false when it is not running. */
        fun reload(): Boolean {
            val s = instance ?: return false
            s.reload()
            return true
        }

        fun queueBytes(): Long? = instance?.metricQueue?.sizeBytes()
        fun queueFiles(): Int? = instance?.metricQueue?.files()?.size
        fun currentConfig(): LoadedConfig? = instance?.loaded
    }
}
