package com.altnautica.gcs.core.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Agent payloads. Every field the agent may omit or null is nullable here; a
// missing reading stays null and is never coerced to zero.

/** `GET /api/version`. */
@Serializable
data class AgentVersion(
    @SerialName("api_version") val apiVersion: String? = null,
    @SerialName("agent_version") val agentVersion: String? = null,
    val capabilities: List<String> = emptyList(),
) {
    fun has(capability: String): Boolean = capability in capabilities

    companion object {
        const val CAP_STATUS_FULL = "status.full"
        const val CAP_BATTERY_HEALTH = "battery.health"
        const val CAP_GROUND_STATION = "ground_station.profile"
        const val CAP_VIDEO_PIPELINE = "video.pipeline"
    }
}

/** `GET /api/status/full`: the consolidated node snapshot. Rarely read blocks stay raw JSON. */
@Serializable
data class StatusFull(
    val version: String? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Double? = null,
    val board: JsonElement? = null,
    val health: JsonObject? = null,
    @SerialName("fc_connected") val fcConnected: Boolean? = null,
    @SerialName("fc_port") val fcPort: String? = null,
    @SerialName("fc_baud") val fcBaud: Int? = null,
    val mavlinkAlive: Boolean? = null,
    val heartbeatAgeS: Double? = null,
    val fcSource: String? = null,
    val fcLinkHint: String? = null,
    val fcReachable: Boolean? = null,
    val fcVariant: String? = null,
    val fcFirmware: String? = null,
    val services: JsonElement? = null,
    val resources: JsonObject? = null,
    val video: VideoBlock? = null,
    val telemetry: TelemetrySnapshot? = null,
    val mesh: JsonObject? = null,
    val radio: JsonObject? = null,
    val profile: String? = null,
    val npuTops: Double? = null,
    val perceptionTier: String? = null,
)

/** The `video` block of `/api/status/full`. `whepUrl` is null unless frames are flowing. */
@Serializable
data class VideoBlock(
    val state: String? = null,
    @SerialName("whep_url") val whepUrl: String? = null,
    @SerialName("hls_url") val hlsUrl: String? = null,
    val recording: Boolean? = null,
    @SerialName("recording_filename") val recordingFilename: String? = null,
    @SerialName("recording_started_at") val recordingStartedAt: String? = null,
    /** Present only on nodes with more than one concurrent leg. */
    val streams: List<VideoStreamLeg>? = null,
)

@Serializable
data class VideoStreamLeg(
    val id: String,
    val role: String? = null,
    val codec: String? = null,
    val whepUrl: String? = null,
    val hls: String? = null,
    /** Null when the agent has not sampled the leg yet. */
    val live: Boolean? = null,
)

/**
 * `GET /api/telemetry`: the router's vehicle snapshot. Angles in radians,
 * heading in degrees, speeds in m/s, altitudes in metres. Empty object when no
 * flight controller is attached, so every block is nullable.
 */
@Serializable
data class TelemetrySnapshot(
    @SerialName("mav_type") val mavType: Int? = null,
    val autopilot: Int? = null,
    @SerialName("vehicle_firmware") val vehicleFirmware: String? = null,
    val armed: Boolean? = null,
    val mode: String? = null,
    val position: SnapshotPosition? = null,
    val velocity: SnapshotVelocity? = null,
    val attitude: SnapshotAttitude? = null,
    val battery: SnapshotBattery? = null,
    val gps: SnapshotGps? = null,
    val rc: SnapshotRc? = null,
    val throttle: Int? = null,
    @SerialName("last_heartbeat") val lastHeartbeat: String? = null,
    @SerialName("position_age_ms") val positionAgeMs: Long? = null,
    @SerialName("mavlink_alive") val mavlinkAlive: Boolean? = null,
    @SerialName("fc_connected") val fcConnected: Boolean? = null,
    @SerialName("heartbeat_age_s") val heartbeatAgeS: Double? = null,
)

@Serializable
data class SnapshotPosition(
    val lat: Double? = null,
    val lon: Double? = null,
    @SerialName("alt_msl") val altMsl: Double? = null,
    @SerialName("alt_rel") val altRel: Double? = null,
    val heading: Double? = null,
)

@Serializable
data class SnapshotVelocity(
    val vx: Double? = null,
    val vy: Double? = null,
    val vz: Double? = null,
    val groundspeed: Double? = null,
    val airspeed: Double? = null,
    val climb: Double? = null,
)

@Serializable
data class SnapshotAttitude(
    val roll: Double? = null,
    val pitch: Double? = null,
    val yaw: Double? = null,
)

@Serializable
data class SnapshotBattery(
    val voltage: Double? = null,
    val current: Double? = null,
    /** `-1` or null when the flight controller does not know. */
    val remaining: Int? = null,
    val temperature: Double? = null,
)

@Serializable
data class SnapshotGps(
    @SerialName("fix_type") val fixType: Int? = null,
    val satellites: Int? = null,
    val eph: Double? = null,
    val epv: Double? = null,
)

@Serializable
data class SnapshotRc(
    val channels: List<Int> = emptyList(),
    val rssi: Int? = null,
)

/** `GET /api/v1/battery`. */
@Serializable
data class BatteryHealth(
    val enabled: Boolean? = null,
    val stale: Boolean? = null,
    @SerialName("updated_at_ms") val updatedAtMs: Long? = null,
    val thresholds: JsonObject? = null,
    val packs: List<BatteryPackHealth> = emptyList(),
)

@Serializable
data class BatteryPackHealth(
    val id: Int,
    val stale: Boolean? = null,
    @SerialName("cell_voltages_v") val cellVoltagesV: List<Double> = emptyList(),
    @SerialName("min_cell_v") val minCellV: Double? = null,
    @SerialName("voltage_v") val voltageV: Double? = null,
    @SerialName("current_a") val currentA: Double? = null,
    @SerialName("remaining_pct") val remainingPct: Int? = null,
    @SerialName("temperature_c") val temperatureC: Double? = null,
    @SerialName("consumed_mah") val consumedMah: Long? = null,
    val prediction: BatteryPrediction? = null,
)

@Serializable
data class BatteryPrediction(
    val state: String? = null,
    /** Seconds until the reserve; null while idle. */
    @SerialName("eta_s") val etaS: Long? = null,
)

/** Body of `POST /api/command`. */
@Serializable
data class CommandRequest(val cmd: String, val args: List<JsonElement> = emptyList())

/** Answer of `POST /api/command`: `status` is `ok` only when the FC accepted. */
@Serializable
data class CommandResponse(
    val status: String? = null,
    val cmd: String? = null,
    val message: String? = null,
    val ack: CommandAck? = null,
) {
    val accepted: Boolean get() = status == "ok"
}

@Serializable
data class CommandAck(
    val observed: Boolean? = null,
    val result: Int? = null,
    @SerialName("result_name") val resultName: String? = null,
    val accepted: Boolean? = null,
)

/** `POST /api/_ws/ticket` answer. */
@Serializable
data class WsTicket(
    val ticket: String,
    @SerialName("expires_at") val expiresAt: Double? = null,
)

/** `GET /api/dashboard/pin/status`. */
@Serializable
data class PinStatus(
    @SerialName("pin_set") val pinSet: Boolean? = null,
    val locked: Boolean? = null,
    @SerialName("locked_until") val lockedUntil: Double? = null,
)

/** Body of `PUT /api/config`. */
@Serializable
data class ConfigWrite(val key: String, val value: JsonElement, val force: Boolean? = null)

/** Body of `PUT /api/plugins/{id}/config`. */
@Serializable
data class PluginConfigWrite(val key: String, val value: JsonElement)
