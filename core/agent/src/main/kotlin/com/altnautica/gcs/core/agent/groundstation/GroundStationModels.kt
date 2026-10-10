package com.altnautica.gcs.core.agent.groundstation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Top-level snapshot returned by the ground-station agent at GET
 * /api/v1/ground-station/status: profile, paired drone, link health,
 * AP/uplink network, system stats, recording flag, role, and mesh.
 *
 * Every top-level block is always present. Leaf values are nullable because
 * the agent reports null for a reading it cannot make (no radio snapshot yet,
 * no paired drone, a stale mesh) rather than a confident zero.
 */
@Serializable
data class StationStatus(
    /** Canonical hyphenated node profile, `ground-station` on this route. */
    val profile: String,
    @SerialName("paired_drone") val pairedDrone: PairedDrone,
    val link: LinkStats,
    val gcs: GcsClients,
    val network: NetworkSnapshot,
    val system: SystemSnapshot,
    val recording: Boolean,
    val role: RoleSnapshot,
    /** Empty on a direct node; populated (or nulled when stale) on a relay or receiver. */
    val mesh: MeshSnapshot = MeshSnapshot(),
)

@Serializable
data class PairedDrone(
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("key_fingerprint") val keyFingerprint: String? = null,
    @SerialName("fc_mode") val fcMode: String? = null,
    @SerialName("battery_pct") val batteryPct: Float? = null,
    @SerialName("gps_sats") val gpsSats: Int? = null,
)

/**
 * Summary radio link stats surfaced inside the status payload. Channel is
 * sourced from agent config; the rest stays null until the radio has written
 * a live snapshot.
 */
@Serializable
data class LinkStats(
    @SerialName("rssi_dbm") val rssiDbm: Int? = null,
    @SerialName("bitrate_mbps") val bitrateMbps: Float? = null,
    @SerialName("fec_recovered") val fecRecovered: Int? = null,
    @SerialName("fec_lost") val fecLost: Int? = null,
    val channel: Int? = null,
)

@Serializable
data class GcsClients(
    val clients: List<String> = emptyList(),
    @SerialName("pic_id") val picId: String? = null,
)

@Serializable
data class NetworkSnapshot(
    @SerialName("ap_ssid") val apSsid: String? = null,
    @SerialName("ap_ip") val apIp: String? = null,
    @SerialName("usb_ip") val usbIp: String? = null,
    @SerialName("uplink_type") val uplinkType: String? = null,
    @SerialName("uplink_reachable") val uplinkReachable: Boolean? = null,
)

@Serializable
data class SystemSnapshot(
    @SerialName("cpu_pct") val cpuPct: Float? = null,
    @SerialName("ram_used_mb") val ramUsedMb: Int? = null,
    @SerialName("ram_total_mb") val ramTotalMb: Int? = null,
    @SerialName("temp_c") val tempC: Float? = null,
    @SerialName("uptime_seconds") val uptimeSeconds: Long? = null,
    @SerialName("agent_version") val agentVersion: String? = null,
)

@Serializable
data class RoleSnapshot(
    val current: String,
    val configured: String? = null,
    val supported: List<String> = emptyList(),
    @SerialName("mesh_capable") val meshCapable: Boolean? = null,
)

@Serializable
data class MeshSnapshot(
    val up: Boolean? = null,
    @SerialName("peer_count") val peerCount: Int? = null,
    @SerialName("selected_gateway") val selectedGateway: String? = null,
    val partition: Boolean? = null,
    @SerialName("mesh_id") val meshId: String? = null,
)

/**
 * Radio view read at GET /api/v1/ground-station/wfb: the channel the radio is
 * actually tuned to and its transmit power, both null when no fresh radio
 * snapshot exists.
 */
@Serializable
data class WfbConfig(
    val channel: Int? = null,
    @SerialName("tx_power_dbm") val txPowerDbm: Int? = null,
)

/**
 * PUT body for /api/v1/ground-station/wfb. The agent stores only the channel
 * and refuses any other field with a 400; [force] is the armed-interlock
 * override and is omitted from the body when null.
 */
@Serializable
data class WfbUpdate(
    val channel: Int,
    val force: Boolean? = null,
)

/**
 * Response from PUT /api/v1/ground-station/wfb: the stored channel and
 * whether the config write landed.
 */
@Serializable
data class WfbWriteResult(
    val channel: Int,
    val persisted: Boolean,
    @SerialName("persist_error") val persistError: String? = null,
)

/**
 * Aggregated network view returned by GET /api/v1/ground-station/network.
 * Covers AP, ethernet, wifi-client, modem, and the active uplink + priority
 * surfaced by the uplink router.
 */
@Serializable
data class NetworkConfig(
    val ap: ApConfig? = null,
    @SerialName("wifi_client") val wifiClient: WifiClientStatus? = null,
    val ethernet: EthernetStatus? = null,
    @SerialName("modem_4g") val modem4g: ModemStatus? = null,
    @SerialName("active_uplink") val activeUplink: String? = null,
    val priority: List<String>? = null,
    @SerialName("share_uplink") val shareUplink: Boolean? = null,
)

@Serializable
data class ApConfig(
    val enabled: Boolean? = null,
    val running: Boolean? = null,
    val ssid: String? = null,
    val channel: Int? = null,
    @SerialName("interface") val iface: String? = null,
    val gateway: String? = null,
    @SerialName("connected_clients") val connectedClients: List<String>? = null,
)

@Serializable
data class WifiClientStatus(
    @SerialName("enabled_on_boot") val enabledOnBoot: Boolean? = null,
    val connected: Boolean? = null,
    val ssid: String? = null,
    val signal: Int? = null,
    val ip: String? = null,
)

@Serializable
data class EthernetStatus(
    val link: Boolean? = null,
    @SerialName("speed_mbps") val speedMbps: Int? = null,
    val ip: String? = null,
    val gateway: String? = null,
)

@Serializable
data class ModemStatus(
    val enabled: Boolean? = null,
    val connected: Boolean? = null,
    val iface: String? = null,
    val ip: String? = null,
    @SerialName("signal_quality") val signalQuality: Int? = null,
    val technology: String? = null,
    val apn: String? = null,
    val operator: String? = null,
    @SerialName("data_used_mb") val dataUsedMb: Int? = null,
    @SerialName("cap_mb") val capMb: Int? = null,
    val percent: Float? = null,
    val state: String? = null,
)

/**
 * PUT body for /api/v1/ground-station/network/ap. A null field is omitted
 * from the body and left unchanged: enabled=null leaves the AP alone, true
 * starts it, false stops it.
 */
@Serializable
data class ApUpdate(
    val enabled: Boolean? = null,
    val ssid: String? = null,
    val passphrase: String? = null,
    val channel: Int? = null,
)

/**
 * POST body for /api/v1/ground-station/recording/start. The hint is an
 * optional filename stem; the agent appends an extension and a
 * timestamp suffix when it conflicts with an existing file.
 */
@Serializable
data class RecordingStartRequest(
    @SerialName("filename_hint") val filenameHint: String? = null,
)

/**
 * Response from POST /api/v1/ground-station/recording/start. Carries the
 * resolved filename, an ISO-8601 UTC start timestamp, and the absolute
 * path on the agent.
 */
@Serializable
data class RecordingStartResponse(
    val filename: String,
    @SerialName("started_at") val startedAt: String,
    val path: String? = null,
)

/**
 * Response from POST /api/v1/ground-station/recording/stop. Carries the
 * filename, an ISO-8601 UTC stop timestamp, the duration in seconds,
 * and the resulting file size in bytes.
 */
@Serializable
data class RecordingStopResponse(
    val filename: String,
    @SerialName("stopped_at") val stoppedAt: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Float? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
)

/**
 * One row from GET /api/v1/ground-station/recording/list. `mtime` is
 * the file modification time as a Unix timestamp in seconds.
 */
@Serializable
data class RecordingInfo(
    val filename: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val mtime: Double,
)

/**
 * Top-level response for GET /api/v1/ground-station/recording/list: the
 * active-recording flag, the current filename when one is in progress, and
 * the disk listing.
 */
@Serializable
data class RecordingListResponse(
    val recording: Boolean,
    @SerialName("current_filename") val currentFilename: String? = null,
    val items: List<RecordingInfo> = emptyList(),
)

/**
 * POST body for /api/v1/ground-station/camera/switch. The id is a small
 * positive integer encoded as a string so the wire contract stays open
 * to named-source variants ("thermal", "rgb", "zoom").
 */
@Serializable
data class CameraSwitchRequest(
    @SerialName("camera_id") val cameraId: String,
)

/**
 * Response from POST /api/v1/ground-station/camera/switch on a
 * multi-camera drone. [sent] says the switch reached the flight controller;
 * [accepted] is null while the controller's acknowledgement is not observed,
 * and [reason] says why.
 */
@Serializable
data class CameraSwitchResponse(
    @SerialName("camera_id") val cameraId: String,
    val sent: Boolean? = null,
    val accepted: Boolean? = null,
    val reason: String? = null,
)

/**
 * Raised when the drone does not advertise multi-camera support (HTTP 501
 * from the camera-switch route), so the UI can show a capability notice
 * instead of a network error.
 */
class CameraNotSupportedError(message: String) : Exception(message)
