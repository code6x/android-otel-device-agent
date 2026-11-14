package dev.code6x.otelagent

import dev.code6x.otelagent.collect.Metrics
import dev.code6x.otelagent.collect.Reading
import dev.code6x.otelagent.export.OtlpEncoder
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OtlpEncoderTest {
    private val resource = OtlpEncoder.resource("1.2.3", "Model X", "Maker", "15", "phone-1", mapOf("location" to "lobby"))
    private val encoder = OtlpEncoder(resource, "1.2.3")

    private fun attrs(list: List<io.opentelemetry.proto.common.v1.KeyValue>) =
        list.associate { it.key to it.value.stringValue }

    @Test fun resourceHasTheConfiguredAttributesAndNoInstallationId() {
        val req = ExportMetricsServiceRequest.parseFrom(encoder.metrics(listOf(Reading(Metrics.SCREEN_ON, 1.0)), 1000))
        val r = attrs(req.getResourceMetrics(0).resource.attributesList)
        assertEquals("android-otel-device-agent", r["service.name"])
        assertEquals("1.2.3", r["service.version"])
        assertEquals("phone-1", r["device.id"])
        assertEquals("Model X", r["device.model.identifier"])
        assertEquals("15", r["os.version"])
        assertEquals("lobby", r["location"])
        assertFalse(r.containsKey("app.installation.id"))
    }

    @Test fun noDeviceIdWhenNotSet() {
        val res = OtlpEncoder.resource("1", "m", "x", "15", "", emptyMap())
        assertFalse(res.attributes.asMap().keys.any { it.key == "device.id" })
    }

    @Test fun gaugesCarryTheReadingTime() {
        val readings = listOf(
            Reading(Metrics.BATTERY_CHARGE, 0.5),
            Reading(Metrics.BATTERY_PLUG, 1.0, mapOf("battery.plug" to "ac")),
            Reading(Metrics.BATTERY_PLUG, 0.0, mapOf("battery.plug" to "none")),
        )
        val req = ExportMetricsServiceRequest.parseFrom(encoder.metrics(readings, 1_700_000_000_123))
        val metrics = req.getResourceMetrics(0).getScopeMetrics(0).metricsList
        assertEquals("dev.code6x.otelagent", req.getResourceMetrics(0).getScopeMetrics(0).scope.name)
        assertEquals(listOf("hw.battery.charge", "device.battery.plug"), metrics.map { it.name })
        assertEquals("1", metrics[0].unit)
        val plug = metrics[1].gauge.dataPointsList
        assertEquals(2, plug.size)
        assertEquals(1_700_000_000_123_000_000, plug[0].timeUnixNano)
        assertEquals("ac", attrs(plug[0].attributesList)["battery.plug"])
        assertEquals(1.0, plug[0].asDouble, 0.0)
    }

    @Test fun bootLogRecord() {
        val req = ExportLogsServiceRequest.parseFrom(encoder.log("device.boot", "device boot", mapOf("device.boot.count" to "3"), 5000, 6000))
        val rec = req.getResourceLogs(0).getScopeLogs(0).getLogRecords(0)
        assertEquals("device boot", rec.body.stringValue)
        assertEquals("device.boot", rec.eventName)
        assertEquals(5_000_000_000, rec.timeUnixNano)
        assertEquals("3", attrs(rec.attributesList)["device.boot.count"])
    }
}
