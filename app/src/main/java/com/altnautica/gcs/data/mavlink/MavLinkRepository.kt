package com.altnautica.gcs.data.mavlink

import android.util.Log
import com.altnautica.gcs.data.flightlog.TlogRecorder
import com.altnautica.gcs.data.pairing.AgentAuthInterceptor
import com.altnautica.gcs.data.pairing.AgentCredentialStore
import com.altnautica.gcs.data.telemetry.ConnectionState
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.TelemetryStore
import io.dronefleet.mavlink.MavlinkConnection
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MavLinkRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val parser: MavLinkParser,
    private val telemetryStore: TelemetryStore,
    private val tlogRecorder: TlogRecorder,
    private val credentials: AgentCredentialStore,
) {

    companion object {
        private const val TAG = "MavLinkRepository"

        /**
         * Default agent MAVLink WebSocket. The agent's MAVLink router owns this
         * listener and serves it at the host root — the path this client used
         * to dial on the control port is served by nothing, so every handshake
         * 404'd and every MAVLink-backed screen stayed dark. Configurable at
         * runtime via [setUrl].
         */
        private const val DEFAULT_WS_URL = "ws://192.168.4.1:8765/"

        /**
         * Fixed reconnect interval. Deliberately not exponential and with no
         * attempt cap: a radio outage or an agent restart must heal on its own
         * within seconds, and a backoff that grows to half a minute turns a
         * two-second blip into an operator reaching for a reload.
         */
        private const val RETRY_INTERVAL_MS = 3000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var session: WebSocketSession? = null

    private val _wsUrl = MutableStateFlow(DEFAULT_WS_URL)
    val wsUrl: StateFlow<String> = _wsUrl.asStateFlow()

    // Output stream for sending MAVLink messages back through the WebSocket
    private val sendOutputStream = ByteArrayOutputStream()
    private var mavlinkConnection: MavlinkConnection? = null

    fun setUrl(url: String) {
        _wsUrl.value = url
    }

    fun connect() {
        disconnect()
        connectionJob = scope.launch {
            connectWithRetry()
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        connectionJob = null
        scope.launch {
            try {
                session?.close()
            } catch (_: Exception) { }
            session = null
            mavlinkConnection = null
        }
        telemetryStore.updateConnection(
            ConnectionState(ConnectionStatus.DISCONNECTED, "Disconnected")
        )
    }

    fun getSendOutputStream(): ByteArrayOutputStream = sendOutputStream

    fun getMavlinkConnection(): MavlinkConnection? = mavlinkConnection

    private suspend fun connectWithRetry() {
        while (scope.isActive) {
            telemetryStore.updateConnection(
                ConnectionState(ConnectionStatus.CONNECTING, "Connecting to ${_wsUrl.value}")
            )

            try {
                // A paired agent's MAVLink proxy refuses an off-box handshake
                // without the pairing key, and the key channel it reads is the
                // same `X-ADOS-Key` header the HTTP control surface uses. A
                // browser cannot set a header on a WebSocket handshake and has
                // to exchange the key for a short-lived ticket first; this is a
                // native client, so it presents the header directly and has
                // nothing to re-mint on each reconnect.
                val ws = httpClient.webSocketSession(_wsUrl.value) {
                    credentials.currentApiKey()?.let { key ->
                        header(AgentAuthInterceptor.KEY_HEADER, key)
                    }
                }
                session = ws

                telemetryStore.updateConnection(
                    ConnectionState(ConnectionStatus.CONNECTED, "Connected")
                )
                Log.i(TAG, "WebSocket connected to ${_wsUrl.value}")

                // Read loop
                for (frame in ws.incoming) {
                    if (frame is Frame.Binary) {
                        processFrame(frame.readBytes())
                    }
                }

                // Connection closed normally
                Log.i(TAG, "WebSocket closed")
                telemetryStore.updateConnection(
                    ConnectionState(ConnectionStatus.LOST, "Connection closed")
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "WebSocket error: ${e.message}")
                telemetryStore.updateConnection(
                    ConnectionState(ConnectionStatus.LOST, "Error: ${e.message}")
                )
            }

            session = null
            mavlinkConnection = null

            // Fixed interval, forever. See RETRY_INTERVAL_MS.
            Log.d(TAG, "Reconnecting in ${RETRY_INTERVAL_MS}ms")
            delay(RETRY_INTERVAL_MS)
        }
    }

    private fun processFrame(bytes: ByteArray) {
        // Record raw bytes to tlog before parsing
        tlogRecorder.recordFrame(bytes)

        try {
            val inputStream = ByteArrayInputStream(bytes)
            val connection = MavlinkConnection.create(
                inputStream,
                sendOutputStream
            )
            mavlinkConnection = connection

            // Read all messages from this frame
            var message = connection.next()
            while (message != null) {
                parser.handleMessage(message)
                message = try {
                    connection.next()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame parse error: ${e.message}")
        }
    }

    suspend fun sendBytes(data: ByteArray) {
        try {
            session?.send(Frame.Binary(true, data))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send: ${e.message}")
        }
    }
}
