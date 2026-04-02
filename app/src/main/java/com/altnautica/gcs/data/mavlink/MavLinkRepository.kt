package com.altnautica.gcs.data.mavlink

import android.util.Log
import com.altnautica.gcs.data.telemetry.ConnectionState
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.TelemetryStore
import io.dronefleet.mavlink.MavlinkConnection
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
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
import kotlin.math.min

@Singleton
class MavLinkRepository @Inject constructor(
    private val httpClient: HttpClient,
    private val parser: MavLinkParser,
    private val telemetryStore: TelemetryStore
) {

    companion object {
        private const val TAG = "MavLinkRepository"
        // Default ADOS Ground Station AP address. Configurable at runtime via setUrl().
        private const val DEFAULT_WS_URL = "ws://192.168.4.1:8080/ws"
        private const val INITIAL_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 30000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectionJob: Job? = null
    private var session: WebSocketSession? = null
    private var currentBackoff = INITIAL_BACKOFF_MS

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
                val ws = httpClient.webSocketSession(_wsUrl.value)
                session = ws
                currentBackoff = INITIAL_BACKOFF_MS

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

            // Exponential backoff
            Log.d(TAG, "Reconnecting in ${currentBackoff}ms")
            delay(currentBackoff)
            currentBackoff = min(currentBackoff * 2, MAX_BACKOFF_MS)
        }
    }

    private fun processFrame(bytes: ByteArray) {
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
