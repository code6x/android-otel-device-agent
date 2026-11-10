package dev.code6x.otelspike

import android.util.Log
import io.opentelemetry.opamp.client.OpampClient
import io.opentelemetry.opamp.client.internal.connectivity.http.OkHttpSender
import io.opentelemetry.opamp.client.internal.request.delay.PeriodicDelay
import io.opentelemetry.opamp.client.internal.connectivity.websocket.OkHttpWebSocket
import io.opentelemetry.opamp.client.internal.request.service.HttpRequestService
import io.opentelemetry.opamp.client.internal.request.service.WebSocketRequestService
import io.opentelemetry.opamp.client.request.service.RequestService
import io.opentelemetry.opamp.client.internal.response.MessageData
import io.opentelemetry.opamp.client.internal.state.State
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import opamp.proto.AgentConfigFile
import opamp.proto.AgentConfigMap
import opamp.proto.ComponentHealth
import opamp.proto.EffectiveConfig
import opamp.proto.RemoteConfigStatus
import opamp.proto.RemoteConfigStatuses
import opamp.proto.ServerErrorResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * The OpAMP client of opentelemetry-java-contrib, over WebSocket or plain HTTP. A remote config is a
 * text with a line `interval_seconds: N` (YAML or JSON style). The client applies it,
 * reports the status APPLIED with the config hash, and reports the effective config.
 */
class Opamp(
    private val settings: Settings,
    private val onInterval: (Int) -> Unit,
) {
    private val tag = "OtelSpike"
    private var client: OpampClient? = null

    private val effective = object : State.EffectiveConfig() {
        override fun get(): EffectiveConfig = effectiveConfig()
    }

    private fun effectiveConfig(): EffectiveConfig {
        val body = "interval_seconds: ${settings.intervalSeconds}\n"
        val file = AgentConfigFile.Builder()
            .body(body.encodeUtf8())
            .content_type("text/yaml")
            .build()
        return EffectiveConfig.Builder()
            .config_map(AgentConfigMap.Builder().config_map(mapOf("" to file)).build())
            .build()
    }

    fun start() {
        if (settings.opampEndpoint.isEmpty()) {
            Log.i(tag, "OPAMP off: no server set")
            return
        }
        if (settings.instanceUid.isEmpty()) {
            settings.instanceUid = OpampClientBuilderUid.newUid()
        }
        val uid = settings.instanceUid.decodeHex()
        val url = settings.opampEndpoint
        // ws:// or wss:// keeps one connection open; http(s):// polls every 30 s.
        val service: RequestService = if (url.startsWith("ws")) {
            WebSocketRequestService.create(
                OkHttpWebSocket.create(url),
                PeriodicDelay.ofFixedDuration(Duration.ofSeconds(30)),
            )
        } else {
            HttpRequestService.create(
                OkHttpSender.create(url),
                PeriodicDelay.ofFixedDuration(Duration.ofSeconds(30)),
                PeriodicDelay.ofFixedDuration(Duration.ofSeconds(30)),
            )
        }
        client = OpampClient.builder()
            .setRequestService(service)
            .setInstanceUid(uid)
            .putIdentifyingAttribute("service.name", "otel-spike")
            .putNonIdentifyingAttribute("device.id", settings.deviceId)
            .putNonIdentifyingAttribute("os.type", "android")
            .enableRemoteConfig()
            .enableEffectiveConfigReporting()
            .setEffectiveConfigState(effective)
            .enableHealthReporting(health(true, "running"))
            .build(Callbacks())
        if (settings.lastConfigHash.isNotEmpty()) {
            // After a restart, report the config that is still in effect.
            client?.setRemoteConfigStatus(
                RemoteConfigStatus.Builder()
                    .last_remote_config_hash(settings.lastConfigHash.decodeHex().toByteString())
                    .status(RemoteConfigStatuses.RemoteConfigStatuses_APPLIED)
                    .build(),
            )
        }
        Log.i(tag, "OPAMP client started, uid=${settings.instanceUid}, last config hash=${settings.lastConfigHash}")
    }

    fun stop() {
        client?.close()
        client = null
    }

    private fun health(ok: Boolean, status: String): ComponentHealth =
        ComponentHealth.Builder().healthy(ok).status(status)
            .start_time_unix_nano(System.currentTimeMillis() * 1_000_000).build()

    private inner class Callbacks : OpampClient.Callbacks {
        override fun onConnect(client: OpampClient) {
            Log.i(tag, "OPAMP connected")
        }

        override fun onConnectFailed(client: OpampClient, throwable: Throwable?) {
            Log.w(tag, "OPAMP connect failed: ${throwable?.javaClass?.simpleName}: ${throwable?.message}")
        }

        override fun onErrorResponse(client: OpampClient, error: ServerErrorResponse) {
            Log.w(tag, "OPAMP server error: ${error.error_message}")
        }

        override fun onMessage(client: OpampClient, data: MessageData) {
            val remote = data.remoteConfig ?: return
            val hash = remote.config_hash.hex()
            // The server sends the offered config again on each poll: apply each hash once.
            if (hash == settings.lastConfigHash) return
            settings.lastConfigHash = hash
            val body = remote.config?.config_map?.values?.joinToString("\n") { it.body.utf8() } ?: ""
            Log.i(tag, "OPAMP config received, hash=$hash, body=${body.replace('\n', ' ')}")
            if (body.isBlank()) {
                // The opamp-go example server offers an empty config until one is set.
                client.setRemoteConfigStatus(
                    RemoteConfigStatus.Builder()
                        .last_remote_config_hash(remote.config_hash)
                        .status(RemoteConfigStatuses.RemoteConfigStatuses_APPLIED)
                        .build(),
                )
                Log.i(tag, "OPAMP empty config, hash=$hash: kept interval ${settings.intervalSeconds} s")
                return
            }
            val interval = Regex("""interval_seconds"?\s*[:=]\s*(\d+)""").find(body)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (interval == null || interval < 5 || interval > 3600) {
                client.setRemoteConfigStatus(
                    RemoteConfigStatus.Builder()
                        .last_remote_config_hash(remote.config_hash)
                        .status(RemoteConfigStatuses.RemoteConfigStatuses_FAILED)
                        .error_message("no interval_seconds between 5 and 3600")
                        .build(),
                )
                Log.w(tag, "OPAMP config FAILED, hash=$hash: no valid interval_seconds")
                return
            }
            val old = settings.intervalSeconds
            settings.intervalSeconds = interval
            onInterval(interval)
            Log.i(tag, "OPAMP config applied: interval $old s -> $interval s")
            client.setRemoteConfigStatus(
                RemoteConfigStatus.Builder()
                    .last_remote_config_hash(remote.config_hash)
                    .status(RemoteConfigStatuses.RemoteConfigStatuses_APPLIED)
                    .build(),
            )
            effective.notifyUpdate()
            val effHash = sha256(effectiveConfig().config_map!!.config_map[""]!!.body.toByteArray())
            Log.i(tag, "OPAMP reported status APPLIED, remote hash=$hash, effective config sha256=$effHash")
        }
    }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).toByteString().hex()
}

private object OpampClientBuilderUid {
    fun newUid(): String =
        io.opentelemetry.opamp.client.OpampClientBuilder.createRandomInstanceUid().toByteString().hex()
}

private fun String.decodeHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
