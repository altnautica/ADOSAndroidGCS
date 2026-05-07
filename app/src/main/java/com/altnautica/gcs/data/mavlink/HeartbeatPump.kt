package com.altnautica.gcs.data.mavlink

import android.util.Log
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.TelemetryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends a 1Hz MAVLink heartbeat from the GCS to the autopilot whenever the
 * MAVLink WebSocket is in the CONNECTED state.
 *
 * ArduPilot fires a GCS-loss failsafe (FS_GCS_ENABLE) when it stops seeing
 * heartbeats from the ground side. Without this pump the GCS stays silent
 * on the link, and a flying drone can RTL or land unexpectedly.
 *
 * The pump observes [TelemetryStore.connection] and starts/stops the timer
 * automatically as the link cycles. Call [start] once at app init and
 * [stop] on shutdown.
 */
@Singleton
class HeartbeatPump @Inject constructor(
    private val telemetryStore: TelemetryStore,
    private val commandSender: MavLinkCommandSender,
) {

    companion object {
        private const val TAG = "HeartbeatPump"
        private const val HEARTBEAT_INTERVAL_MS = 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The heartbeat-sending coroutine while the link is up. Null when idle. */
    private var heartbeatJob: Job? = null

    /** The supervisor coroutine that watches connection state. */
    private var supervisorJob: Job? = null

    fun start() {
        if (supervisorJob != null) return
        supervisorJob = scope.launch {
            telemetryStore.connection.collectLatest { state ->
                if (state.status == ConnectionStatus.CONNECTED) {
                    startBeating()
                } else {
                    stopBeating()
                }
            }
        }
    }

    fun stop() {
        stopBeating()
        supervisorJob?.cancel()
        supervisorJob = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun startBeating() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            Log.i(TAG, "Heartbeat pump started")
            while (isActive) {
                try {
                    telemetryStore.lastGcsHeartbeatSentMs = System.currentTimeMillis()
                    commandSender.sendHeartbeat()
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat send failed: ${e.message}")
                }
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private fun stopBeating() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** Test hook: returns true while the pump is actively sending. */
    internal fun isBeating(): Boolean = heartbeatJob?.isActive == true
}
