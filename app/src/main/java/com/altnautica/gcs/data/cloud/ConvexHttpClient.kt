package com.altnautica.gcs.data.cloud

import android.util.Log
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.ConnectionState
import com.altnautica.gcs.data.telemetry.ConnectionStatus
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP poll fallback for when MQTT is unavailable.
 *
 * Polls the Convex HTTP relay at convex.altnautica.com every 5 seconds.
 * The agent POSTs status to /agent/status, and this client reads from
 * the cmd_droneStatus Convex table via a query function.
 *
 * Also supports sending commands: the GCS enqueues commands via
 * /agent/commands, and the agent polls and ACKs them.
 */
@Singleton
class ConvexHttpClient @Inject constructor(
    private val telemetryStore: TelemetryStore,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "ConvexHttpClient"
        private const val CONVEX_URL = "https://convex.altnautica.com"
        private const val POLL_INTERVAL_MS = 5_000L
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    private val _polling = MutableStateFlow(false)
    val polling: StateFlow<Boolean> = _polling.asStateFlow()

    private var scope: CoroutineScope? = null

    fun startPolling(deviceId: String) {
        stopPolling()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _polling.value = true

        telemetryStore.updateConnection(
            ConnectionState(ConnectionStatus.CONNECTING, "Convex HTTP polling")
        )

        scope?.launch {
            var consecutiveErrors = 0

            while (isActive) {
                try {
                    val response = okHttpClient.newCall(
                        Request.Builder()
                            .url("$CONVEX_URL/agent/status?deviceId=$deviceId")
                            .header("Accept", "application/json")
                            .build()
                    ).execute()

                    response.use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string()
                            if (!body.isNullOrBlank()) {
                                val json = JSONObject(body)
                                parseStatusResponse(json)
                                consecutiveErrors = 0
                                telemetryStore.updateConnection(
                                    ConnectionState(ConnectionStatus.CONNECTED, "Cloud HTTP (5s poll)")
                                )
                            }
                        } else {
                            Log.w(TAG, "Poll failed: HTTP ${resp.code}")
                            consecutiveErrors++
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll error: ${e.message}")
                    consecutiveErrors++
                }

                if (consecutiveErrors >= 3) {
                    telemetryStore.updateConnection(
                        ConnectionState(ConnectionStatus.LOST, "Convex HTTP errors ($consecutiveErrors)")
                    )
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Send a command to the drone via Convex HTTP relay.
     * The agent polls /agent/commands and picks it up.
     */
    fun sendCommand(deviceId: String, command: String, params: JSONObject? = null) {
        scope?.launch {
            try {
                val payload = JSONObject().apply {
                    put("deviceId", deviceId)
                    put("command", command)
                    if (params != null) put("params", params)
                    put("timestamp", System.currentTimeMillis())
                }

                val request = Request.Builder()
                    .url("$CONVEX_URL/agent/commands")
                    .header("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                response.use { resp ->
                    if (resp.isSuccessful) {
                        Log.d(TAG, "Command sent: $command")
                    } else {
                        Log.w(TAG, "Command failed: HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command send error: ${e.message}")
            }
        }
    }

    private fun parseStatusResponse(json: JSONObject) {
        // The Convex HTTP relay returns the same JSON shape as the MQTT status topic
        if (json.has("lat") && json.has("lon")) {
            telemetryStore.updatePosition(
                PositionState(
                    lat = json.optDouble("lat", 0.0),
                    lon = json.optDouble("lon", 0.0),
                    altMsl = json.optDouble("alt", 0.0).toFloat(),
                    altRel = json.optDouble("relAlt", 0.0).toFloat(),
                    heading = json.optInt("heading", 0),
                )
            )
        }

        if (json.has("voltage")) {
            telemetryStore.updateBattery(
                BatteryState(
                    voltage = json.optDouble("voltage", 0.0).toFloat(),
                    current = json.optDouble("current", 0.0).toFloat(),
                    remaining = json.optInt("remaining", -1),
                )
            )
        }

        if (json.has("satellites") || json.has("fixType")) {
            telemetryStore.updateGps(
                GpsState(
                    fixType = json.optInt("fixType", 0),
                    satellites = json.optInt("satellites", 0),
                    hdop = json.optInt("hdop", 0),
                    lat = json.optDouble("lat", 0.0),
                    lon = json.optDouble("lon", 0.0),
                    alt = json.optDouble("alt", 0.0).toFloat(),
                )
            )
        }

        if (json.has("customMode")) {
            telemetryStore.updateFlightMode(FlightMode.fromNumber(json.optInt("customMode", 0)))
        }

        if (json.has("armed")) {
            telemetryStore.updateArmed(json.optBoolean("armed", false))
        }
    }

    fun stopPolling() {
        scope?.cancel()
        scope = null
        _polling.value = false
    }
}
