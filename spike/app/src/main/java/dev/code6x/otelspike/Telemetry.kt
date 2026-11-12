package dev.code6x.otelspike

import android.app.Application
import android.os.Build
import android.util.Log
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.android.OpenTelemetryRumBuilder
import io.opentelemetry.android.config.OtelRumConfig
import io.opentelemetry.android.features.diskbuffering.DiskBufferingConfig
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleGauge
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Two pipelines side by side, so one network cut tests both:
 * - library: opentelemetry-android with disk buffering on. The OTLP metric exporter is put in
 *   with addMetricExporterCustomizer; the library puts its disk buffer in front of it.
 * - own: a plain SdkMeterProvider whose exporter writes each export to a file queue
 *   (OwnQueue.kt); a sender posts the files.
 * The resource attribute spike.buffer tells them apart.
 */
object Telemetry {
    private const val TAG = "OtelSpike"

    @Volatile private var rum: OpenTelemetryRum? = null

    /** The library pipeline (opentelemetry-android disk buffer) and the own-queue pipeline. */
    lateinit var library: Instruments
    lateinit var own: Instruments
    private var ownProvider: SdkMeterProvider? = null
    private var sender: QueueSender? = null

    @Synchronized
    fun start(app: Application, settings: Settings) {
        if (rum != null) return
        val endpoint = settings.otlpEndpoint.trimEnd('/') + "/opentelemetry/v1/metrics"
        val config = OtelRumConfig()
            .setTracingDisabled()
            .setLoggingDisabled()
            .disableInstrumentationDiscovery()
            .disableNetworkAttributes()
            .disableScreenAttributes()
            .disableSdkInitializationEvents()
            .setDiskBufferingConfig(DiskBufferingConfig.create(true))
        val resource = Resource.create(
            Attributes.builder()
                .put("service.name", "otel-spike")
                .put("service.version", BuildConfig.VERSION_NAME)
                .put("device.id", settings.deviceId)
                .put("device.model.identifier", Build.MODEL)
                .put("os.version", Build.VERSION.RELEASE)
                .put("location", "spike-test")
                .build(),
        )
        val built = OpenTelemetryRumBuilder.create(app, config)
            .mergeResource(resource.merge(bufferAttr("library")))
            .addMetricExporterCustomizer {
                LoggingExporter(
                    OtlpHttpMetricExporter.builder()
                        .setEndpoint(endpoint)
                        .setTimeout(10, TimeUnit.SECONDS)
                        .build(),
                )
            }
            .build()
        rum = built
        library = Instruments(built.openTelemetry.getMeter("otel-spike"))

        val queueDir = File(app.filesDir, "otlp-queue")
        val queueSender = QueueSender(queueDir, endpoint).also { it.start() }
        sender = queueSender
        val provider = SdkMeterProvider.builder()
            .setResource(Resource.getDefault().merge(resource).merge(bufferAttr("own")))
            .registerMetricReader(
                // Long period: in practice each tick's flush makes the export.
                PeriodicMetricReader.builder(FileQueueExporter(queueDir, 20L * 1024 * 1024) { queueSender.kick() })
                    .setInterval(Duration.ofHours(1))
                    .build(),
            )
            .build()
        ownProvider = provider
        own = Instruments(provider.get("otel-spike"))
        Log.i(TAG, "telemetry started, disk buffering on, endpoint path /opentelemetry/v1/metrics")
    }

    /** Collect now, so each tick becomes one point (the library reader's own 60 s period also runs). */
    fun flush() {
        (rum?.openTelemetry as? OpenTelemetrySdk)?.sdkMeterProvider?.forceFlush()?.join(10, TimeUnit.SECONDS)
        ownProvider?.forceFlush()?.join(10, TimeUnit.SECONDS)
    }

    private fun bufferAttr(kind: String) = Resource.create(Attributes.of(AttributeKey.stringKey("spike.buffer"), kind))
}

/** The same instruments in each pipeline; each tick records into both. */
class Instruments(meter: Meter) {
    val batteryLevel: DoubleGauge = meter.gaugeBuilder("device.battery.level").setUnit("%")
        .setDescription("Battery level").build()
    val batteryVoltage: DoubleGauge = meter.gaugeBuilder("device.battery.voltage").setUnit("V")
        .setDescription("Battery voltage").build()
    val batteryTemperature: DoubleGauge = meter.gaugeBuilder("device.battery.temperature").setUnit("Cel")
        .setDescription("Battery temperature").build()
    val screenOn: DoubleGauge = meter.gaugeBuilder("device.screen.on").setUnit("1")
        .setDescription("1 when the screen is on").build()
    val tickInterval: DoubleGauge = meter.gaugeBuilder("spike.tick.interval").setUnit("s")
        .setDescription("Sampling interval in effect").build()
    val ticks: LongCounter = meter.counterBuilder("spike.ticks").setUnit("{tick}")
        .setDescription("Samples taken since the app started").build()

    fun record(level: Double, plugged: String, voltage: Double, temp: Double, screen: Boolean, interval: Int) {
        batteryLevel.set(level, Attributes.of(PLUGGED, plugged))
        batteryVoltage.set(voltage)
        batteryTemperature.set(temp)
        screenOn.set(if (screen) 1.0 else 0.0)
        tickInterval.set(interval.toDouble())
        ticks.add(1)
    }

    companion object {
        val PLUGGED: AttributeKey<String> = AttributeKey.stringKey("battery.plugged")
    }
}

/** Logs every export result of the network exporter, so the log shows the network cut. */
private class LoggingExporter(private val delegate: MetricExporter) : MetricExporter {
    override fun export(metrics: Collection<MetricData>): CompletableResultCode {
        val result = delegate.export(metrics)
        result.whenComplete {
            Log.i("OtelSpike", "network export of ${metrics.size} metrics: " +
                if (result.isSuccess) "ok" else "FAILED")
        }
        return result
    }
    override fun flush(): CompletableResultCode = delegate.flush()
    override fun shutdown(): CompletableResultCode = delegate.shutdown()
    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        delegate.getAggregationTemporality(instrumentType)
}
