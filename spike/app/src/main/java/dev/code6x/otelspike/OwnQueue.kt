package dev.code6x.otelspike

import android.util.Log
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The smallest own queue: each export is written as one file that holds the OTLP/HTTP
 * protobuf request body. A sender posts the files in order and deletes a file only
 * after the receiver answered 2xx. The files are in the app's files folder, so they
 * live through a network cut and an app restart.
 */
class FileQueueExporter(
    private val dir: File,
    private val maxBytes: Long,
    private val onWritten: () -> Unit,
) : MetricExporter {
    private val tag = "OtelSpike"

    init {
        dir.mkdirs()
    }

    override fun export(metrics: Collection<MetricData>): CompletableResultCode {
        if (metrics.isEmpty()) return CompletableResultCode.ofSuccess()
        return try {
            val tmp = File(dir, "write.tmp")
            tmp.outputStream().use { MetricsRequestMarshaler.create(metrics).writeBinaryTo(it) }
            // Name by time, so the files sort in the order they were written.
            val name = String.format("%020d.pb", System.currentTimeMillis() * 1000 + (System.nanoTime() / 1000 % 1000))
            tmp.renameTo(File(dir, name))
            trim()
            onWritten()
            CompletableResultCode.ofSuccess()
        } catch (e: Exception) {
            Log.e(tag, "own queue: write failed", e)
            CompletableResultCode.ofFailure()
        }
    }

    /** Drops the oldest files when the queue is larger than maxBytes. */
    private fun trim() {
        val files = queued(dir)
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
            Log.w(tag, "own queue: full, dropped ${f.name}")
        }
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        AggregationTemporality.CUMULATIVE

    companion object {
        fun queued(dir: File): List<File> =
            dir.listFiles { f -> f.name.endsWith(".pb") }?.sortedBy { it.name } ?: emptyList()
    }
}

/** Posts the queued files, oldest first. Stops at the first failure and tries again later. */
class QueueSender(private val dir: File, private val endpoint: String) {
    private val tag = "OtelSpike"
    private val client = OkHttpClient.Builder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val protobuf = "application/x-protobuf".toMediaType()

    fun start() {
        executor.scheduleWithFixedDelay({ sendAll() }, 5, 15, TimeUnit.SECONDS)
    }

    fun kick() {
        executor.execute { sendAll() }
    }

    private fun sendAll() {
        var sent = 0
        var failed: String? = null
        for (f in FileQueueExporter.queued(dir)) {
            val code = try {
                val req = Request.Builder().url(endpoint).post(f.readBytes().toRequestBody(protobuf)).build()
                client.newCall(req).execute().use { it.code }
            } catch (e: Exception) {
                failed = "${e.javaClass.simpleName}: ${e.message}"
                break
            }
            if (code in 200..299) {
                f.delete()
                sent++
            } else if (code in 400..499 && code != 408 && code != 429) {
                // The receiver will never take this file: drop it, or it blocks the queue.
                f.delete()
                Log.w(tag, "own queue: HTTP $code, dropped ${f.name}")
            } else {
                failed = "HTTP $code"
                break
            }
        }
        val left = FileQueueExporter.queued(dir).size
        if (failed != null) {
            Log.i(tag, "own queue: send FAILED ($failed), sent $sent, $left files wait")
        } else if (sent > 0) {
            Log.i(tag, "own queue: sent $sent, $left files wait")
        }
    }
}
