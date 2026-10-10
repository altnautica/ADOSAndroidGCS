package com.altnautica.gcs.core.agent.mavlink

import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raw MAVLink over the agent's WebSocket proxy (`ws://<host>:8765/`, binary
 * frames, `X-ADOS-Key` on the handshake). Re-dials with 1 → 10 s backoff until
 * [close]; a refused key ends in [TransportState.Failed].
 *
 * [resolve] is asked for the endpoint before every dial so an address change
 * found by discovery is picked up on reconnect.
 */
class AgentMavlinkTransport(
    private val client: OkHttpClient,
    private val resolve: suspend () -> AgentEndpoint?,
    /** Test seam: dial this URL instead of the derived proxy URL. */
    private val wsUrlOverride: String? = null,
    dispatcherScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MavlinkByteTransport {

    private val scope = dispatcherScope
    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = INCOMING_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    init {
        scope.launch { run() }
    }

    private suspend fun run() {
        var backoff = MIN_BACKOFF_MS
        while (!closed) {
            _state.value = TransportState.Connecting
            val endpoint = resolve()
            if (endpoint == null) {
                _state.value = TransportState.Failed("node is not paired or has no known address")
                return
            }
            val ended = CompletableDeferred<DialEnd>()
            val request = Request.Builder().url(wsUrlOverride ?: wsUrl(endpoint)).apply {
                endpoint.apiKey?.let { header(AgentHttp.KEY_HEADER, it) }
            }.build()
            val ws = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        _state.value = TransportState.Open
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        _incoming.tryEmit(bytes.toByteArray())
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(NORMAL_CLOSE, null)
                        ended.complete(if (code == UNAUTHORIZED_CLOSE) DialEnd.Refused else DialEnd.Dropped(reason))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        ended.complete(if (code == UNAUTHORIZED_CLOSE) DialEnd.Refused else DialEnd.Dropped(reason))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val code = response?.code
                        ended.complete(if (code == 401 || code == 403) DialEnd.Refused else DialEnd.Dropped(t.message))
                    }
                },
            )
            socket = ws
            val end = ended.await()
            val wasOpen = _state.value == TransportState.Open
            socket = null
            if (closed) break
            if (end is DialEnd.Refused) {
                _state.value = TransportState.Failed("the node refused this device's key")
                return
            }
            if (wasOpen) backoff = MIN_BACKOFF_MS
            _state.value = TransportState.Connecting
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    override suspend fun write(bytes: ByteArray): WriteResult {
        val ws = socket
        if (ws == null || _state.value != TransportState.Open) return WriteResult.Failed("not connected")
        return if (ws.send(bytes.toByteString())) WriteResult.Sent else WriteResult.Failed("send queue full or closing")
    }

    override suspend fun close() {
        closed = true
        socket?.close(NORMAL_CLOSE, null)
        socket = null
        _state.value = TransportState.Closed("closed")
        scope.cancel()
    }

    private sealed interface DialEnd {
        data object Refused : DialEnd
        data class Dropped(val reason: String?) : DialEnd
    }

    companion object {
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 10_000L
        const val NORMAL_CLOSE = 1000
        const val UNAUTHORIZED_CLOSE = 4401
        private const val INCOMING_BUFFER = 512

        /** `ws://<host>:8765/` for the agent behind [endpoint] (the control port is not reused). */
        fun wsUrl(endpoint: AgentEndpoint): String {
            val scheme = if (endpoint.baseUrl.isHttps) "wss" else "ws"
            val host = endpoint.baseUrl.host
            val literal = if (host.contains(':')) "[$host]" else host
            return "$scheme://$literal:${AgentEndpoint.MAVLINK_WS_PORT}/"
        }
    }
}

/**
 * Opens [AgentMavlinkTransport]s for [Reach.Lan] (the node's own proxy) and
 * [Reach.ViaGround] (the ground station's proxy; its router relays the carried
 * drone's MAVLink).
 */
@Singleton
class AgentMavlinkTransportProvider @Inject constructor(
    private val endpoints: AgentEndpoints,
    private val http: AgentHttp,
) : TransportProvider {

    override fun supports(reach: Reach): Boolean = reach is Reach.Lan || reach is Reach.ViaGround

    override suspend fun open(node: NodeSummary, reach: Reach): MavlinkByteTransport {
        val target = when (reach) {
            is Reach.Lan -> node.id
            is Reach.ViaGround -> reach.groundId
            else -> throw IllegalArgumentException("unsupported reach $reach")
        }
        return AgentMavlinkTransport(http.webSocketClient, resolve = { endpoints.endpointFor(target) })
    }
}
