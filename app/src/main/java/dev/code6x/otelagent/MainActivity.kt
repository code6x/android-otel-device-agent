package dev.code6x.otelagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.code6x.otelagent.config.ConfigException
import dev.code6x.otelagent.config.ConfigLoader
import dev.code6x.otelagent.config.ConfigStore
import java.util.Date

/**
 * The only screen: status, setup checklist and the config in use. Also the share target
 * for config files and text (for example from a QR code scanner).
 */
class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var configText: TextView
    private lateinit var startStop: Button
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(t: String) = TextView(this).apply {
            text = t
            textSize = 20f
            setPadding(0, pad, 0, pad / 2)
        }
        startStop = Button(this).apply { setOnClickListener { toggleService() } }
        status = TextView(this).apply { setTextIsSelectable(true) }
        setupBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        configText = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        root.addView(header("Status"))
        root.addView(startStop)
        root.addView(status)
        root.addView(header("Setup checklist"))
        root.addView(setupBox)
        root.addView(header("Config in use"))
        root.addView(Button(this).apply {
            text = "Import config file"
            setOnClickListener { pickFile() }
        })
        root.addView(Button(this).apply {
            text = "Back to defaults"
            setOnClickListener { confirmReset() }
        })
        root.addView(configText)
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun time(ms: Long) = if (ms == 0L) "never" else DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(ms)).toString()

    private fun refresh() {
        val running = AgentService.instance != null
        startStop.text = if (running) "Stop" else "Start"
        val loaded = AgentService.currentConfig() ?: ConfigStore(this).load()
        val sb = StringBuilder()
        sb.append("Service: ").append(if (running) "running since ${time(AgentState.processStartMs)}" else "stopped").append('\n')
        sb.append("Timer: ").append(AgentState.timerMode.ifEmpty { "-" }).append('\n')
        sb.append("Endpoint: ").append(if (loaded.config.exporter.endpoint.isEmpty()) "not set (data stays in the queue)" else loaded.config.exporter.endpoint).append('\n')
        sb.append("Last export: ").append(time(AgentState.lastExportMs))
        if (AgentState.lastExportResult.isNotEmpty()) sb.append(" (").append(AgentState.lastExportResult).append(')')
        sb.append('\n')
        val files = AgentService.queueFiles()
        val bytes = AgentService.queueBytes()
        sb.append("Queue: ").append(if (files == null) "-" else "$files files, ${bytes!! / 1024} KiB").append('\n')
        sb.append("Last reading: ").append(time(AgentState.lastReadingMs)).append("\n\n")
        for (name in dev.code6x.otelagent.config.AgentConfig.COLLECTORS) {
            val on = loaded.config.collector(name)
            sb.append(name).append(if (on.enabled) " (every ${on.intervalSeconds} s)" else " (off)")
            val st = AgentState.collectors[name]
            if (st == null) {
                sb.append(": no reading yet\n")
                continue
            }
            sb.append(" at ").append(time(st.timeMs)).append('\n')
            st.error?.let { sb.append("  error: ").append(it).append('\n') }
            for (r in st.readings.filter { it.attributes.isEmpty() || it.value != 0.0 }) {
                sb.append("  ").append(r.def.name)
                if (r.attributes.isNotEmpty()) sb.append(r.attributes.values.joinToString(",", "{", "}"))
                sb.append(" = ").append(fmt(r.value))
                if (r.def.unit != "1") sb.append(' ').append(r.def.unit)
                sb.append('\n')
            }
        }
        status.text = sb.toString()

        setupBox.removeAllViews()
        for (item in Setup.items(this, loaded.config.wakePolicy)) {
            val mark = when (item.done) {
                true -> "[ok] "
                false -> "[ ! ] "
                null -> "[ ? ] "
            }
            setupBox.addView(TextView(this).apply { text = mark + item.title + "\n" + item.help })
            item.open?.let { open ->
                setupBox.addView(Button(this).apply {
                    text = "Open"
                    setOnClickListener {
                        try {
                            startActivity(open)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(this@MainActivity, "This phone has no such screen", Toast.LENGTH_LONG).show()
                        }
                    }
                })
            }
        }
        val c = StringBuilder()
        loaded.entries.forEach { c.append(ConfigLoader.display(it)).append('\n') }
        loaded.warnings.forEach { c.append("warning: ").append(it).append('\n') }
        configText.text = c.toString()
    }

    private fun fmt(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else String.format(java.util.Locale.ROOT, "%.3f", v)

    private fun toggleService() {
        if (AgentService.instance != null) AgentService.stop(this) else AgentService.start(this)
        ui.postDelayed({ refresh() }, 500)
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, PICK)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK && resultCode == RESULT_OK) data?.data?.let { readUri(it)?.let(::importText) }
    }

    private fun readUri(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { s ->
            val bytes = s.readBytes()
            if (bytes.size > MAX_BYTES) throw ConfigException("file larger than ${MAX_BYTES / 1024} KiB")
            String(bytes, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Toast.makeText(this, "Could not read the file: ${e.message}", Toast.LENGTH_LONG).show()
        null
    }

    /** A config from another app (share sheet, QR scanner, file manager): ask before using it. */
    private fun handleShare(i: Intent?) {
        if (i == null) return
        val text = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
                ?: @Suppress("DEPRECATION") (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { readUri(it) }
            Intent.ACTION_VIEW -> i.data?.let { readUri(it) }
            else -> null
        } ?: return
        setIntent(Intent(this, MainActivity::class.java))
        AlertDialog.Builder(this)
            .setTitle("Use this config?")
            .setMessage(text.take(4000))
            .setPositiveButton("Import") { _, _ -> importText(text) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importText(text: String) {
        try {
            val loaded = ConfigStore(this).import(text)
            AgentService.reload()
            val w = if (loaded.warnings.isEmpty()) "" else "\n" + loaded.warnings.joinToString("\n")
            Toast.makeText(this, "Config imported$w", Toast.LENGTH_LONG).show()
        } catch (e: ConfigException) {
            AlertDialog.Builder(this).setTitle("Config not imported").setMessage(e.message).setPositiveButton("OK", null).show()
        } catch (e: java.io.IOException) {
            Toast.makeText(this, "Could not save: ${e.message}", Toast.LENGTH_LONG).show()
        }
        refresh()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle("Back to defaults?")
            .setMessage("The local config file is removed. The endpoint is then not set.")
            .setPositiveButton("Reset") { _, _ ->
                ConfigStore(this).reset()
                AgentService.reload()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val PICK = 2
        private const val MAX_BYTES = 64 * 1024
    }
}
