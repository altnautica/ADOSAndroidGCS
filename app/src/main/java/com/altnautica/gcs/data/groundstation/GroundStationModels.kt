package com.altnautica.gcs.data.groundstation

import com.google.gson.annotations.SerializedName

/**
 * Top-level snapshot returned by the ground-station agent at GET
 * /api/v1/ground-station/status. Mirrors the agent's OLED-aligned schema:
 * profile, paired drone, link health, AP/uplink network, system stats,
 * recording flag, role, and mesh.
 */
data class StationStatus(
    val profile: String = "ground_station",
    @SerializedName("paired_drone") val pairedDrone: PairedDrone = PairedDrone(),
    val link: LinkStats = LinkStats(),
    val gcs: GcsClients = GcsClients(),
    val network: NetworkSnapshot = NetworkSnapshot(),
    val system: SystemSnapshot = SystemSnapshot(),
    val recording: Boolean = false,
    val role: RoleSnapshot = RoleSnapshot(),
    val mesh: MeshSnapshot = MeshSnapshot(),
)

data class PairedDrone(
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("key_fingerprint") val keyFingerprint: String? = null,
    @SerializedName("fc_mode") val fcMode: String? = null,
    @SerializedName("battery_pct") val batteryPct: Float? = null,
    @SerializedName("gps_sats") val gpsSats: Int? = null,
)

/**
 * Summary radio link stats surfaced inside the status payload. Channel is
 * sourced from agent config; the rest is best-effort and may be null/zero
 * until live telemetry plumbing fills it in.
 */
data class LinkStats(
    @SerializedName("rssi_dbm") val rssiDbm: Int? = null,
    @SerializedName("bitrate_mbps") val bitrateMbps: Float? = null,
    @SerializedName("fec_recovered") val fecRecovered: Int = 0,
    @SerializedName("fec_lost") val fecLost: Int = 0,
    val channel: Int? = null,
)

data class GcsClients(
    val clients: List<String> = emptyList(),
    @SerializedName("pic_id") val picId: String? = null,
)

data class NetworkSnapshot(
    @SerializedName("ap_ssid") val apSsid: String? = null,
    @SerializedName("ap_ip") val apIp: String? = null,
    @SerializedName("usb_ip") val usbIp: String? = null,
    @SerializedName("uplink_type") val uplinkType: String? = null,
    @SerializedName("uplink_reachable") val uplinkReachable: Boolean = false,
)

data class SystemSnapshot(
    @SerializedName("cpu_pct") val cpuPct: Float = 0f,
    @SerializedName("ram_used_mb") val ramUsedMb: Int = 0,
    @SerializedName("ram_total_mb") val ramTotalMb: Int = 0,
    @SerializedName("temp_c") val tempC: Float? = null,
    @SerializedName("uptime_seconds") val uptimeSeconds: Long = 0,
    @SerializedName("agent_version") val agentVersion: String = "",
)

data class RoleSnapshot(
    val current: String = "direct",
    val configured: String = "direct",
    val supported: List<String> = listOf("direct", "relay", "receiver"),
    @SerializedName("mesh_capable") val meshCapable: Boolean = false,
)

data class MeshSnapshot(
    val up: Boolean = false,
    @SerializedName("peer_count") val peerCount: Int = 0,
    @SerializedName("selected_gateway") val selectedGateway: String? = null,
    val partition: Boolean = false,
    @SerializedName("mesh_id") val meshId: String? = null,
)

/**
 * Radio config read/write at /api/v1/ground-station/wfb. Channel is the
 * 5 GHz channel number, FEC is a "k/n" string ("8/12" by default), and
 * bitrate_profile names a preset on the agent side.
 */
data class WfbConfig(
    val channel: Int = 0,
    @SerializedName("bitrate_profile") val bitrateProfile: String = "default",
    val fec: String = "8/12",
)

/**
 * Aggregated network view returned by GET /api/v1/ground-station/network.
 * Covers AP, ethernet, wifi-client, modem, and the active uplink + priority
 * surfaced by the uplink router.
 */
data class NetworkConfig(
    val ap: ApConfig = ApConfig(),
    @SerializedName("wifi_client") val wifiClient: WifiClientStatus = WifiClientStatus(),
    val ethernet: EthernetStatus = EthernetStatus(),
    @SerializedName("modem_4g") val modem4g: ModemStatus = ModemStatus(),
    @SerializedName("active_uplink") val activeUplink: String? = null,
    val priority: List<String> = emptyList(),
    @SerializedName("share_uplink") val shareUplink: Boolean = false,
)

data class ApConfig(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val ssid: String? = null,
    val channel: Int? = null,
    @SerializedName("interface") val iface: String? = null,
    val gateway: String? = null,
    @SerializedName("connected_clients") val connectedClients: List<String> = emptyList(),
)

data class WifiClientStatus(
    @SerializedName("enabled_on_boot") val enabledOnBoot: Boolean = false,
    val connected: Boolean = false,
    val ssid: String? = null,
    val signal: Int? = null,
    val ip: String? = null,
)

data class EthernetStatus(
    val link: Boolean = false,
    @SerializedName("speed_mbps") val speedMbps: Int? = null,
    val ip: String? = null,
    val gateway: String? = null,
)

data class ModemStatus(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val iface: String? = null,
    val ip: String? = null,
    @SerializedName("signal_quality") val signalQuality: Int? = null,
    val technology: String? = null,
    val apn: String? = null,
    val operator: String? = null,
    @SerializedName("data_used_mb") val dataUsedMb: Int = 0,
    @SerializedName("cap_mb") val capMb: Int = 0,
    val percent: Float = 0f,
    val state: String = "unknown",
)

/**
 * PUT body for /api/v1/ground-station/wfb. Any null field is left
 * unchanged on the agent side.
 */
data class WfbUpdate(
    val channel: Int? = null,
    @SerializedName("bitrate_profile") val bitrateProfile: String? = null,
    val fec: String? = null,
)

/**
 * PUT body for /api/v1/ground-station/network/ap. Driving the AP off
 * the JSON null/non-null distinction matches the agent's HostapdManager
 * semantics: enabled=null leaves the unit alone, true starts, false stops.
 */
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
data class RecordingStartRequest(
    @SerializedName("filename_hint") val filenameHint: String? = null,
)

/**
 * Response from POST /api/v1/ground-station/recording/start. Carries the
 * resolved filename, an ISO-8601 UTC start timestamp, and the absolute
 * path on the agent.
 */
data class RecordingStartResponse(
    val filename: String,
    @SerializedName("started_at") val startedAt: String,
    val path: String,
)

/**
 * Response from POST /api/v1/ground-station/recording/stop. Carries the
 * filename, an ISO-8601 UTC stop timestamp, the duration in seconds,
 * and the resulting file size in bytes.
 */
data class RecordingStopResponse(
    val filename: String,
    @SerializedName("stopped_at") val stoppedAt: String,
    @SerializedName("duration_seconds") val durationSeconds: Float,
    @SerializedName("size_bytes") val sizeBytes: Long,
)

/**
 * One row from GET /api/v1/ground-station/recording/list. `mtime` is
 * the file modification time as a Unix timestamp in seconds.
 */
data class RecordingInfo(
    val filename: String,
    @SerializedName("size_bytes") val sizeBytes: Long,
    val mtime: Double,
)

/**
 * Top-level response for GET /api/v1/ground-station/recording/list. The
 * agent returns the active-recording flag, the current filename when
 * one is in progress, and the disk listing.
 */
data class RecordingListResponse(
    val recording: Boolean = false,
    @SerializedName("current_filename") val currentFilename: String? = null,
    val items: List<RecordingInfo> = emptyList(),
)

/**
 * POST body for /api/v1/ground-station/camera/switch. The id is a small
 * positive integer encoded as a string so the wire contract stays open
 * to future named-source variants ("thermal", "rgb", "zoom").
 */
data class CameraSwitchRequest(
    @SerializedName("camera_id") val cameraId: String,
)

/**
 * Response from POST /api/v1/ground-station/camera/switch on a
 * multi-camera drone. Single-camera drones return HTTP 501 instead and
 * the GCS surfaces the capability hint without parsing this body.
 */
data class CameraSwitchResponse(
    @SerializedName("camera_id") val cameraId: String,
    val accepted: Boolean,
    val reason: String? = null,
)
