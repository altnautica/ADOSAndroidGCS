package com.altnautica.gcs.core.agent.sensors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Ground-station GNSS and LoRa payloads. `present: false` means the service is
// not fitted; every reading the agent omits stays null.

/** `GET /api/v1/gnss`. */
@Serializable
data class GnssStatus(
    val present: Boolean = false,
    val receivers: List<GnssReceiver> = emptyList(),
    val base: GnssBase? = null,
    val heading: GnssHeading? = null,
)

@Serializable
data class GnssReceiver(
    val id: String,
    val source: String? = null,
    val model: String? = null,
    /** `none` | `2d` | `3d` | `dgps` | `rtk_float` | `rtk_fixed`. */
    val fix: String? = null,
    val sats: Int? = null,
    val hdop: Double? = null,
    @SerialName("lat_deg") val latDeg: Double? = null,
    @SerialName("lon_deg") val lonDeg: Double? = null,
    @SerialName("h_ellipsoid_m") val hEllipsoidM: Double? = null,
    @SerialName("h_msl_m") val hMslM: Double? = null,
    /** `none` | `float` | `fixed`. */
    val rtk: String? = null,
    @SerialName("pps_locked") val ppsLocked: Boolean? = null,
)

/** RTK base state; also the body `PUT /api/v1/gnss/base` answers with. */
@Serializable
data class GnssBase(
    /** `off` | `survey` | `fixed`. */
    val mode: String? = null,
    /** `off` | `surveying` | `fixed` | `failed`. */
    val state: String? = null,
    @SerialName("elapsed_s") val elapsedS: Long? = null,
    @SerialName("min_dur_s") val minDurS: Long? = null,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("target_accuracy_m") val targetAccuracyM: Double? = null,
    val fixed: GnssPosition? = null,
    @SerialName("rtcm_out") val rtcmOut: RtcmOut? = null,
)

@Serializable
data class GnssPosition(
    @SerialName("lat_deg") val latDeg: Double,
    @SerialName("lon_deg") val lonDeg: Double,
    @SerialName("h_ellipsoid_m") val hEllipsoidM: Double,
)

@Serializable
data class RtcmOut(
    @SerialName("msgs_per_s") val msgsPerS: Double? = null,
    @SerialName("bytes_total") val bytesTotal: Long? = null,
    val targets: Int? = null,
)

@Serializable
data class GnssHeading(
    val available: Boolean = false,
    val deg: Double? = null,
    @SerialName("accuracy_deg") val accuracyDeg: Double? = null,
    @SerialName("baseline_m") val baselineM: Double? = null,
)

/** `GET /api/v1/lora` (and the answer of `PUT /api/v1/lora`). */
@Serializable
data class LoraStatus(
    val present: Boolean = false,
    /** `e22` | `meshtastic` | `loopback`. */
    val transport: String? = null,
    @SerialName("module_band") val moduleBand: String? = null,
    val region: LoraRegion? = null,
    val channel: LoraChannel? = null,
    @SerialName("node_id") val nodeId: Int? = null,
    @SerialName("tx_allowed") val txAllowed: Boolean = false,
    /**
     * `region_unset` | `module_band_mismatch` | `duty_exhausted` | `dwell_time` |
     * `lbt_busy` | `power_over_limit`, or null when transmit is allowed.
     */
    @SerialName("blocked_reason") val blockedReason: String? = null,
    val governor: LoraGovernor? = null,
    val tx: LoraTx? = null,
    val rx: LoraRx? = null,
    val meshtastic: LoraMeshtastic? = null,
)

@Serializable
data class LoraRegion(
    val code: String,
    val name: String? = null,
    @SerialName("plan_id") val planId: String? = null,
    /** Where the region came from: `lora.region` or `network.regulatory.region`. */
    val source: String? = null,
    @SerialName("band_mhz") val bandMhz: List<Double> = emptyList(),
    val citation: String? = null,
)

@Serializable
data class LoraChannel(
    @SerialName("freq_mhz") val freqMhz: Double? = null,
    @SerialName("bw_khz") val bwKhz: Int? = null,
    val sf: Int? = null,
    @SerialName("erp_mw") val erpMw: Double? = null,
    @SerialName("max_erp_mw") val maxErpMw: Double? = null,
)

@Serializable
data class LoraGovernor(
    val rules: List<LoraRule> = emptyList(),
    @SerialName("next_tx_allowed_ms") val nextTxAllowedMs: Long? = null,
    @SerialName("last_refusal") val lastRefusal: LoraRefusal? = null,
)

/** One rule the active band plan declares; fields apply per [kind]. */
@Serializable
data class LoraRule(
    /** `duty_cycle` | `dwell_time` | `lbt` | `max_power`. */
    val kind: String,
    // duty_cycle
    @SerialName("window_s") val windowS: Long? = null,
    @SerialName("limit_pct") val limitPct: Double? = null,
    @SerialName("used_pct") val usedPct: Double? = null,
    @SerialName("airtime_ms_window") val airtimeMsWindow: Long? = null,
    @SerialName("budget_ms_window") val budgetMsWindow: Long? = null,
    val exhausted: Boolean? = null,
    @SerialName("sub_band") val subBand: String? = null,
    // dwell_time
    @SerialName("limit_ms") val limitMs: Long? = null,
    @SerialName("per_s") val perS: Long? = null,
    @SerialName("last_tx_ms") val lastTxMs: Long? = null,
    // lbt
    @SerialName("threshold_dbm") val thresholdDbm: Double? = null,
    @SerialName("listen_us") val listenUs: Long? = null,
    @SerialName("last_rssi_dbm") val lastRssiDbm: Double? = null,
    @SerialName("busy_count") val busyCount: Long? = null,
    // max_power
    @SerialName("limit_mw") val limitMw: Double? = null,
    @SerialName("configured_mw") val configuredMw: Double? = null,
    // dwell_time, max_power
    val ok: Boolean? = null,
)

@Serializable
data class LoraRefusal(val rule: String, @SerialName("at_ms") val atMs: Long? = null)

@Serializable
data class LoraTx(
    val frames: Long? = null,
    val refused: Long? = null,
    @SerialName("refused_by_rule") val refusedByRule: Map<String, Long> = emptyMap(),
)

@Serializable
data class LoraRx(
    val frames: Long? = null,
    @SerialName("crc_errors") val crcErrors: Long? = null,
)

@Serializable
data class LoraMeshtastic(
    val enabled: Boolean = false,
    val connected: Boolean = false,
    val port: String? = null,
    @SerialName("region_preset") val regionPreset: String? = null,
)

/** `GET /api/v1/lora/regions`: the compiled band-plan table. */
@Serializable
data class LoraRegions(val regions: List<LoraRegionPlan> = emptyList())

@Serializable
data class LoraRegionPlan(
    val code: String,
    val name: String? = null,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("band_mhz") val bandMhz: List<Double> = emptyList(),
    val rules: List<String> = emptyList(),
    @SerialName("supported_by_module") val supportedByModule: Boolean = false,
    /** Expected ELRS regulatory domain per band (`2g4`, `900`); a null value is unverified. */
    @SerialName("elrs_domains") val elrsDomains: Map<String, String?> = emptyMap(),
)

/** `GET /api/v1/ground-station/crsf`: only the module list is read here. */
@Serializable
data class CrsfStatus(val modules: List<CrsfModule> = emptyList())

@Serializable
data class CrsfModule(
    val id: String,
    val role: String? = null,
    val connected: Boolean = false,
    /** ELRS regulatory domain the module reports, verbatim (`ISM2G4`, `CE_LBT`, `FCC915`, ...); null when unknown. */
    val domain: String? = null,
)

/** `GET /api/v1/lora/beacons`, newest first. */
@Serializable
data class LoraBeacons(val beacons: List<LoraBeacon> = emptyList())

@Serializable
data class LoraBeacon(
    @SerialName("node_id") val nodeId: Int,
    val kind: String? = null,
    @SerialName("lat_deg") val latDeg: Double? = null,
    @SerialName("lon_deg") val lonDeg: Double? = null,
    @SerialName("alt_m") val altM: Double? = null,
    @SerialName("battery_pct") val batteryPct: Int? = null,
    val mode: String? = null,
    @SerialName("rssi_dbm") val rssiDbm: Double? = null,
    @SerialName("snr_db") val snrDb: Double? = null,
    @SerialName("received_at_ms") val receivedAtMs: Long? = null,
)
