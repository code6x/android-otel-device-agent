package dev.code6x.otelagent.export

import dev.code6x.otelagent.collect.Reading
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.exporter.internal.otlp.logs.LogsRequestMarshaler
import io.opentelemetry.exporter.internal.otlp.metrics.MetricsRequestMarshaler
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableDoublePointData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableGaugeData
import io.opentelemetry.sdk.metrics.internal.data.ImmutableMetricData
import io.opentelemetry.sdk.resources.Resource
import java.io.ByteArrayOutputStream

/**
 * Builds OTLP/HTTP protobuf request bodies. The readings are turned into gauge points by hand
 * (not through a MeterProvider), so each point carries the wall-clock time of its reading,
 * and a collector that is turned off sends nothing at once.
 */
class OtlpEncoder(val resource: Resource, version: String) {
    private val scope = InstrumentationScopeInfo.builder(SCOPE).setVersion(version).build()

    fun metrics(readings: List<Reading>, epochMs: Long): ByteArray {
        val nanos = epochMs * 1_000_000
        val data = readings.groupBy { it.def }.map { (def, rs) ->
            val points = rs.map { ImmutableDoublePointData.create(nanos, nanos, attributes(it.attributes), it.value) }
            ImmutableMetricData.createDoubleGauge(resource, scope, def.name, def.description, def.unit, ImmutableGaugeData.create(points))
        }
        val out = ByteArrayOutputStream()
        MetricsRequestMarshaler.create(data).writeBinaryTo(out)
        return out.toByteArray()
    }

    fun log(eventName: String, body: String, attrs: Map<String, String>, epochMs: Long, observedMs: Long): ByteArray {
        val record = SimpleLog(resource, scope, eventName, body, attributes(attrs), epochMs * 1_000_000, observedMs * 1_000_000)
        val out = ByteArrayOutputStream()
        LogsRequestMarshaler.create(listOf(record)).writeBinaryTo(out)
        return out.toByteArray()
    }

    companion object {
        const val SCOPE = "dev.code6x.otelagent"

        fun attributes(map: Map<String, String>): Attributes {
            val b: AttributesBuilder = Attributes.builder()
            map.forEach { (k, v) -> b.put(k, v) }
            return b.build()
        }

        /** The resource: fixed device facts, then the attributes from config, then device.id. */
        fun resource(
            serviceVersion: String,
            model: String,
            manufacturer: String,
            osVersion: String,
            deviceId: String,
            extra: Map<String, String>,
        ): Resource {
            val m = linkedMapOf(
                "service.name" to "android-otel-device-agent",
                "service.version" to serviceVersion,
                "device.model.identifier" to model,
                "device.manufacturer" to manufacturer,
                "os.name" to "Android",
                "os.type" to "linux",
                "os.version" to osVersion,
                "telemetry.sdk.language" to "java",
                "telemetry.sdk.name" to "opentelemetry",
            )
            m.putAll(extra)
            if (deviceId.isNotEmpty()) m["device.id"] = deviceId
            return Resource.create(attributes(m))
        }
    }
}

private class SimpleLog(
    private val resource: Resource,
    private val scope: InstrumentationScopeInfo,
    private val event: String,
    private val text: String,
    private val attrs: Attributes,
    private val ts: Long,
    private val observed: Long,
) : LogRecordData {
    override fun getResource() = resource
    override fun getInstrumentationScopeInfo() = scope
    override fun getTimestampEpochNanos() = ts
    override fun getObservedTimestampEpochNanos() = observed
    override fun getSpanContext(): SpanContext = SpanContext.getInvalid()
    override fun getSeverity() = Severity.INFO
    override fun getSeverityText() = "INFO"

    @Deprecated("Deprecated in Java")
    override fun getBody(): Body = Body.string(text)
    override fun getAttributes() = attrs
    override fun getTotalAttributeCount() = attrs.size()
    override fun getEventName() = event
}
