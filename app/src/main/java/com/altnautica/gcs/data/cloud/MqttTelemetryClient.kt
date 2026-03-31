     STDIN
   1 package com.altnautica.gcs.data.cloud
   2 
   3 import android.util.Log
   4 import com.altnautica.gcs.data.telemetry.AttitudeState
   5 import com.altnautica.gcs.data.telemetry.BatteryState
   6 import com.altnautica.gcs.data.telemetry.ConnectionState
   7 import com.altnautica.gcs.data.telemetry.ConnectionStatus
   8 import com.altnautica.gcs.data.telemetry.FlightMode
   9 import com.altnautica.gcs.data.telemetry.GpsState
  10 import com.altnautica.gcs.data.telemetry.PositionState
  11 import com.altnautica.gcs.data.telemetry.TelemetryStore
  12 import com.altnautica.gcs.data.telemetry.VfrState
  13 import com.google.gson.Gson
  14 import com.google.gson.JsonObject
  15 import io.ktor.client.HttpClient
  16 import io.ktor.client.plugins.websocket.webSocket
  17 import io.ktor.websocket.Frame
  18 import io.ktor.websocket.readBytes
  19 import kotlinx.coroutines.CoroutineScope
  20 import kotlinx.coroutines.Dispatchers
  21 import kotlinx.coroutines.Job
  22 import kotlinx.coroutines.SupervisorJob
  23 import kotlinx.coroutines.flow.MutableStateFlow
  24 import kotlinx.coroutines.flow.StateFlow
  25 import kotlinx.coroutines.flow.asStateFlow
  26 import kotlinx.coroutines.isActive
  27 import kotlinx.coroutines.launch
  28 import javax.inject.Inject
  29 import javax.inject.Singleton
  30 
  31 /**
  32  * Subscribes to MQTT telemetry via the WebSocket transport at mqtt.altnautica.com.
  33  * Parses JSON status/telemetry messages from the ADOS Drone Agent and pushes
  34  * them into the TelemetryStore, so the rest of the app works identically to
  35  * direct MAVLink mode.
  36  *
  37  * Topics (per DEC-070):
  38  *   ados/{deviceId}/status    -- heartbeat + mode + armed + battery (2Hz)
  39  *   ados/{deviceId}/telemetry -- attitude, position, GPS, VFR (2Hz)
  40  */
  41 @Singleton
  42 class MqttTelemetryClient @Inject constructor(
  43     private val httpClient: HttpClient,
  44     private val telemetryStore: TelemetryStore,
  45 ) {
  46 
  47     companion object {
  48         private const val TAG = "MqttTelemetryClient"
  49         private const val MQTT_WS_URL = "wss://mqtt.altnautica.com/mqtt"
  50     }
  51 
  52     private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  53     private var sessionJob: Job? = null
  54     private val gson = Gson()
  55 
  56     private val _connected = MutableStateFlow(false)
  57     val connected: StateFlow<Boolean> = _connected.asStateFlow()
  58 
  59     fun connect(deviceId: String) {
  60         if (sessionJob?.isActive == true) return
  61         Log.i(TAG, "Connecting to MQTT relay for device $deviceId")
  62 
  63         telemetryStore.updateConnection(
  64             ConnectionState(ConnectionStatus.CONNECTING, "Cloud relay...")
  65         )
  66 
  67         sessionJob = scope.launch {
  68             try {
  69                 httpClient.webSocket(MQTT_WS_URL) {
  70                     _connected.value = true
  71                     telemetryStore.updateConnection(
  72                         ConnectionState(ConnectionStatus.CONNECTED, "Cloud relay")
  73                     )
  74                     Log.i(TAG, "MQTT WebSocket connected")
  75 
  76                     // Send MQTT SUBSCRIBE for this device's topics.
  77                     // The MQTT broker at mqtt.altnautica.com supports MQTT-over-WebSocket.
  78                     // For Phase 5 we send raw MQTT CONNECT + SUBSCRIBE packets.
  79                     // For now we rely on the MQTT-to-Convex bridge pattern where
  80                     // the broker publishes JSON text frames to WebSocket subscribers.
  81                     val subscribeTopic = """{"subscribe":["ados/$deviceId/status","ados/$deviceId/telemetry"]}"""
  82                     send(Frame.Text(subscribeTopic))
  83 
  84                     for (frame in incoming) {
  85                         if (!isActive) break
  86                         when (frame) {
  87                             is Frame.Text -> {
  88                                 val text = frame.readBytes().decodeToString()
  89                                 parseAndApply(text, deviceId)
  90                             }
  91                             else -> { /* binary/ping handled by Ktor */ }
  92                         }
  93                     }
  94                 }
  95             } catch (e: Exception) {
  96                 Log.e(TAG, "MQTT connection failed: ${e.message}")
  97                 telemetryStore.updateConnection(
  98                     ConnectionState(ConnectionStatus.LOST, "Cloud relay lost: ${e.message}")
  99                 )
 100             } finally {
 101                 _connected.value = false
 102                 Log.i(TAG, "MQTT disconnected")
 103             }
 104         }
 105     }
 106 
 107     fun disconnect() {
 108         sessionJob?.cancel()
 109         sessionJob = null
 110         _connected.value = false
 111         telemetryStore.updateConnection(
 112             ConnectionState(ConnectionStatus.DISCONNECTED, "Cloud relay disconnected")
 113         )
 114     }
 115 
 116     /**
 117      * Parse JSON telemetry from the MQTT relay and update the TelemetryStore.
 118      * The agent publishes two message types:
 119      *   - status: { armed, mode, battery_voltage, battery_current, battery_remaining }
 120      *   - telemetry: { roll, pitch, yaw, lat, lon, alt_msl, alt_rel, heading,
 121      *                   groundspeed, airspeed, throttle, climb, gps_fix, satellites, hdop }
 122      */
 123     private fun parseAndApply(json: String, deviceId: String) {
 124         try {
 125             val obj = gson.fromJson(json, JsonObject::class.java)
 126             val topic = obj.get("topic")?.asString ?: return
 127             val payload = obj.getAsJsonObject("payload") ?: return
 128 
 129             when {
 130                 topic.endsWith("/status") -> applyStatus(payload)
 131                 topic.endsWith("/telemetry") -> applyTelemetry(payload)
 132             }
 133         } catch (e: Exception) {
 134             Log.w(TAG, "Failed to parse MQTT message: ${e.message}")
 135         }
 136     }
 137 
 138     private fun applyStatus(p: JsonObject) {
 139         p.get("armed")?.asBoolean?.let { telemetryStore.updateArmed(it) }
 140         p.get("mode")?.asInt?.let { telemetryStore.updateFlightMode(FlightMode.fromNumber(it)) }
 141         telemetryStore.updateBattery(
 142             BatteryState(
 143                 voltage = p.get("battery_voltage")?.asFloat ?: 0f,
 144                 current = p.get("battery_current")?.asFloat ?: 0f,
 145                 remaining = p.get("battery_remaining")?.asInt ?: -1,
 146             )
 147         )
 148     }
 149 
 150     private fun applyTelemetry(p: JsonObject) {
 151         telemetryStore.updateAttitude(
 152             AttitudeState(
 153                 roll = p.get("roll")?.asFloat ?: 0f,
 154                 pitch = p.get("pitch")?.asFloat ?: 0f,
 155                 yaw = p.get("yaw")?.asFloat ?: 0f,
 156             )
 157         )
 158         telemetryStore.updatePosition(
 159             PositionState(
 160                 lat = p.get("lat")?.asDouble ?: 0.0,
 161                 lon = p.get("lon")?.asDouble ?: 0.0,
 162                 altMsl = p.get("alt_msl")?.asFloat ?: 0f,
 163                 altRel = p.get("alt_rel")?.asFloat ?: 0f,
 164                 heading = p.get("heading")?.asInt ?: 0,
 165             )
 166         )
 167         telemetryStore.updateVfr(
 168             VfrState(
 169                 airspeed = p.get("airspeed")?.asFloat ?: 0f,
 170                 groundspeed = p.get("groundspeed")?.asFloat ?: 0f,
 171                 heading = p.get("heading")?.asInt ?: 0,
 172                 throttle = p.get("throttle")?.asInt ?: 0,
 173                 alt = p.get("alt_rel")?.asFloat ?: 0f,
 174                 climb = p.get("climb")?.asFloat ?: 0f,
 175             )
 176         )
 177         telemetryStore.updateGps(
 178             GpsState(
 179                 fixType = p.get("gps_fix")?.asInt ?: 0,
 180                 satellites = p.get("satellites")?.asInt ?: 0,
 181                 hdop = p.get("hdop")?.asInt ?: 0,
 182                 lat = p.get("lat")?.asDouble ?: 0.0,
 183                 lon = p.get("lon")?.asDouble ?: 0.0,
 184                 alt = p.get("alt_msl")?.asFloat ?: 0f,
 185             )
 186         )
 187     }
 188 }
