package dev.code6x.otelagent

import android.util.Log
import dev.code6x.otelagent.config.ConfigStore
import dev.code6x.otelagent.config.Layers
import dev.code6x.otelagent.config.RemoteResult
import io.opentelemetry.opamp.client.OpampClient
import io.opentelemetry.opamp.client.OpampClientBuilder
import io.opentelemetry.opamp.client.internal.connectivity.http.OkHttpSender
import io.opentelemetry.opamp.client.internal.connectivity.websocket.OkHttpWebSocket
import io.opentelemetry.opamp.client.internal.request.delay.PeriodicDelay
import io.opentelemetry.opamp.client.internal.request.service.HttpRequestService
import io.opentelemetry.opamp.client.internal.request.service.WebSocketRequestService
import io.opentelemetry.opamp.client.internal.response.MessageData
import io.opentelemetry.opamp.client.internal.state.State
import io.opentelemetry.opamp.client.request.service.RequestService
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import opamp.proto.AgentConfigFile
import opamp.proto.AgentConfigMap
import opamp.proto.ComponentHealth
import opamp.proto.EffectiveConfig
import opamp.proto.RemoteConfigStatus
import opamp.proto.RemoteConfigStatuses
import opamp.proto.ServerErrorResponse
import java.time.Duration

/**
 * The OpAMP client (opentelemetry-java-contrib), over WebSocket (ws://, wss://) or HTTP polling
 * (http://, https://). The server sends a config document of the same shape as the local file.
 * A good one is kept on the phone and applied; a bad one is refused (status FAILED) and the last
 * good one stays; an empty one changes nothing. Reports the effective config and the health.
 */
class OpampAgent(
    val endpoint: String,
    private val store: ConfigStore,
    private val instanceUid: ByteArray,
    private val effectiveJson: () -> String,
    private val onApplied: () -> Unit,
) {
    private var client: OpampClient? = null

    /** Hash (hex) of the last config offer we handled, so each offer is handled once. */
    @Volatile private var lastSeen = store.opampHash()

    private val effective = object : State.EffectiveConfig() {
        override fun get(): EffectiveConfig = EffectiveConfig.Builder()
            .config_map(AgentConfigMap.Builder().config_map(mapOf("" to AgentConfigFile.Builder()
                .body(effectiveJson().encodeUtf8()).content_type("application/json").build())).build())
            .build()
    }

    fun start() {
        val delay = PeriodicDelay.ofFixedDuration(Duration.ofSeconds(30))
        val service: RequestService = if (endpoint.startsWith("ws")) {
            WebSocketRequestService.create(OkHttpWebSocket.create(endpoint), delay)
        } else {
            HttpRequestService.create(OkHttpSender.create(endpoint), delay, delay)
        }
        val c = OpampClient.builder()
            .setRequestService(service)
            .setInstanceUid(instanceUid)
            .putIdentifyingAttribute("service.name", "android-otel-device-agent")
            .putIdentifyingAttribute("service.version", BuildConfig.VERSION_NAME)
            .putNonIdentifyingAttribute("os.type", "android")
            .enableRemoteConfig()
            .enableEffectiveConfigReporting()
            .setEffectiveConfigState(effective)
            .enableHealthReporting(health())
            .build(Callbacks())
        client = c
        val hash = store.opampHash()
        if (hash.isNotEmpty()) {
            // After a restart: report the config that is still in effect.
            c.setRemoteConfigStatus(status(hash.decodeHex(), RemoteConfigStatuses.RemoteConfigStatuses_APPLIED, ""))
        }
        AgentState.opampStatus = "connecting"
        Log.i(TAG, "OpAMP client started, last config hash ${hash.ifEmpty { "none" }}")
    }

    fun stop() {
        try {
            client?.close()
        } catch (e: Exception) {
            Log.w(TAG, "OpAMP close: ${e.message}")
        }
        client = null
        Log.i(TAG, "OpAMP client stopped")
    }

    /** The config in use changed: report it. */
    fun configChanged() = effective.notifyUpdate()

    /** Reports version, last export and buffer size. */
    fun updateHealth() {
        client?.setHealth(health())
    }

    private fun health(): ComponentHealth {
        val export = if (AgentState.lastExportMs == 0L) "none" else
            java.time.Instant.ofEpochMilli(AgentState.lastExportMs).toString() + " " + AgentState.lastExportResult
        val ok = AgentState.lastExportResult.isEmpty() || AgentState.lastExportResult.startsWith("ok")
        val now = System.currentTimeMillis() * 1_000_000
        return ComponentHealth.Builder()
            .healthy(ok)
            .start_time_unix_nano(AgentState.processStartMs * 1_000_000)
            .status_time_unix_nano(now)
            .status("version ${BuildConfig.VERSION_NAME}; last export $export; buffer ${AgentState.queueBytes} bytes in ${AgentState.queueFiles} files")
            .last_error(if (ok) "" else AgentState.lastExportResult)
            .build()
    }

    private fun status(hash: ByteArray, s: RemoteConfigStatuses, error: String) = RemoteConfigStatus.Builder()
        .last_remote_config_hash(hash.toByteString()).status(s).error_message(error).build()

    private inner class Callbacks : OpampClient.Callbacks {
        override fun onConnect(client: OpampClient) {
            AgentState.opampStatus = "connected"
            Log.i(TAG, "OpAMP connected")
        }

        override fun onConnectFailed(client: OpampClient, throwable: Throwable?) {
            AgentState.opampStatus = "connect failed: ${throwable?.javaClass?.simpleName}: ${throwable?.message}"
            Log.w(TAG, "OpAMP ${AgentState.opampStatus}")
        }

        override fun onErrorResponse(client: OpampClient, error: ServerErrorResponse) {
            Log.w(TAG, "OpAMP server error: ${error.error_message}")
        }

        override fun onMessage(client: OpampClient, data: MessageData) {
            val remote = data.remoteConfig ?: return
            val hash = remote.config_hash.hex()
            // The server offers the same config again on each poll: handle each hash once.
            if (hash == lastSeen) return
            lastSeen = hash
            val files = remote.config?.config_map.orEmpty()
            val body = files[""]?.body?.utf8() ?: files.values.singleOrNull()?.body?.utf8()
            val result = when {
                files.isEmpty() -> RemoteResult.Kept
                body == null -> RemoteResult.Refused("send one config file (name \"\")")
                else -> Layers.checkRemote(store.baseLayers(), body)
            }
            when (result) {
                is RemoteResult.Applied -> {
                    store.saveOpamp(body!!, hash)
                    client.setRemoteConfigStatus(status(remote.config_hash.toByteArray(), RemoteConfigStatuses.RemoteConfigStatuses_APPLIED, ""))
                    AgentState.opampStatus = "connected; config $hash applied"
                    Log.i(TAG, "OpAMP config applied, hash $hash")
                    onApplied()
                }
                is RemoteResult.Refused -> {
                    client.setRemoteConfigStatus(status(remote.config_hash.toByteArray(), RemoteConfigStatuses.RemoteConfigStatuses_FAILED, result.reason))
                    AgentState.opampStatus = "connected; config $hash refused: ${result.reason}"
                    Log.w(TAG, "OpAMP config refused, hash $hash: ${result.reason}; the last good config stays")
                }
                RemoteResult.Kept -> Log.i(TAG, "OpAMP empty config, hash $hash: the last good config stays")
            }
        }
    }

    companion object {
        const val TAG = "OtelAgent"

        fun newUid(): String = OpampClientBuilder.createRandomInstanceUid().toByteString().hex()

        fun String.decodeHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
