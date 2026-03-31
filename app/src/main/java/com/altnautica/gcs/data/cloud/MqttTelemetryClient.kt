package com.altnautica.gcs.data.cloud

import android.util.Log
import com.altnautica.gcs.data.telemetry.AttitudeState
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.ConnectionState
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.altnautica.gcs.data.telemetry.VfrState
import com.google.gson.Gson
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscribes to MQTT telemetry via the WebSocket transport at mqtt.altnautica.com.
 * Parses JSON status/telemetry messages from the ADOS Drone Agent and pushes
 * them into the TelemetryStore, so the rest of the app works identically to
 * direct MAVLink mode.
 *
 * Topics (per DEC-070):
 *   ados/{deviceId}/status    -- heartbeat + mode + armed + battery (2Hz)
 *   ados/{deviceId}/telemetry -- attitude, position, GPS, VFR (2Hz)
 */
@Singleton
class MqttTelemetryClient @Inject constructor(
    private val httpClient: HttpClient,
    private val telemetryStore: TelemetryStore,
) {

    companion object {
        private const val TAG = "MqttTelemetryClient"
        private const val MQTT_WS_URL = "wss://mqtt.altnautica.com/mqtt"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private val gson = Gson()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun connect(deviceId: String) {
        if (sessionJob?.isActive == true) return
        Log.i(TAG, "Connecting to MQTT relay for device $deviceId")

        telemetryStore.updateConnection(
            ConnectionState(ConnectionStatus.CONNECTING, "Cloud relay...")
        )

        sessionJob = scope.launch {
            try {
                httpClient.webSocket(MQTT_WS_URL) {
                    _connected.value = true
                    telemetryStore.updateConnection(
                        ConnectionState(ConnectionStatus.CONNECTED, "Cloud relay")
                    )
                    Log.i(TAG, "MQTT WebSocket connected")

                    // Send MQTT SUBSCRIBE for this device's topics.
                    // The MQTT broker at mqtt.altnautica.com supports MQTT-over-WebSocket.
                    // For Phase 5 we send raw MQTT CONNECT + SUBSCRIBE packets.
                    // For now we rely on the MQTT-to-Convex bridge pattern where
                    // the broker publishes JSON text frames to WebSocket subscribers.
                    val subscribeTopic = """{"subscribe":["ados/$deviceId/status","ados/$deviceId/telemetry"]}"""
                    send(Frame.Text(subscribeTopic))

                    for (frame in incoming) {
                        if (!isActive) break
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readBytes().decodeToString()
                                parseAndApply(text, deviceId)
                            }
                            else -> { /* binary/ping handled by Ktor */ }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "MQTT connection failed: ${e.message}")
                telemetryStore.updateConnection(
                    ConnectionState(ConnectionStatus.LOST, "Cloud relay lost: ${e.message}")
                )
            } finally {
                _connected.value = false
                Log.i(TAG, "MQTT disconnected")
            }
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        _connected.value = false
        telemetryStore.updateConnection(
            ConnectionState(ConnectionStatus.DISCONNECTED, "Cloud relay disconnected")
        )
    }

    /**
     * Parse JSON telemetry from the MQTT relay and update the TelemetryStore.
     * The agent publishes two message types:
     *   - status: { armed, mode, battery_voltage, battery_current, battery_remaining }
     *   - telemetry: { roll, pitch, yaw, lat, lon, alt_msl, alt_rel, heading,
     *                   groundspeed, airspeed, throttle, climb, gps_fix, satellites, hdop }
     */
    private fun parseAndApply(json: String, deviceId: String) {
        try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            val topic = obj.get("topic")?.asString ?: return
            val payload = obj.getAsJsonObject("payload") ?: return

            when {
                topic.endsWith("/status") -> applyStatus(payload)
                topic.endsWith("/telemetry") -> applyTelemetry(payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse MQTT message: ${e.message}")
        }
    }

    private fun applyStatus(p: JsonObject) {
        p.get("armed")?.asBoolean?.let { telemetryStore.updateArmed(it) }
        p.get("mode")?.asInt?.let { telemetryStore.updateFlightMode(FlightMode.fromNumber(it)) }
        telemetryStore.updateBattery(
            BatteryState(
                voltage = p.get("battery_voltage")?.asFloat ?: 0f,
                current = p.get("battery_current")?.asFloat ?: 0f,
                remaining = p.get("battery_remaining")?.asInt ?: -1,
            )
        )
    }

    private fun applyTelemetry(p: JsonObject) {
        telemetryStore.updateAttitude(
            AttitudeState(
                roll = p.get("roll")?.asFloat ?: 0f,
                pitch = p.get("pitch")?.asFloat ?: 0f,
                yaw = p.get("yaw")?.asFloat ?: 0f,
            )
        )
        telemetryStore.updatePosition(
            PositionState(
                lat = p.get("lat")?.asDouble ?: 0.0,
                lon = p.get("lon")?.asDouble ?: 0.0,
                altMsl = p.get("alt_msl")?.asFloat ?: 0f,
                altRel = p.get("alt_rel")?.asFloat ?: 0f,
                heading = p.get("heading")?.asInt ?: 0,
            )
        )
        telemetryStore.updateVfr(
            VfrState(
                airspeed = p.get("airspeed")?.asFloat ?: 0f,
                groundspeed = p.get("groundspeed")?.asFloat ?: 0f,
                heading = p.get("heading")?.asInt ?: 0,
                throttle = p.get("throttle")?.asInt ?: 0,
                alt = p.get("alt_rel")?.asFloat ?: 0f,
                climb = p.get("climb")?.asFloat ?: 0f,
            )
        )
        telemetryStore.updateGps(
            GpsState(
                fixType = p.get("gps_fix")?.asInt ?: 0,
                satellites = p.get("satellites")?.asInt ?: 0,
                hdop = p.get("hdop")?.asInt ?: 0,
                lat = p.get("lat")?.asDouble ?: 0.0,
                lon = p.get("lon")?.asDouble ?: 0.0,
                alt = p.get("alt_msl")?.asFloat ?: 0f,
            )
        )
    }
}
