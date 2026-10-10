package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.feature.node.common.arr
import com.altnautica.gcs.feature.node.common.bool
import com.altnautica.gcs.feature.node.common.double
import com.altnautica.gcs.feature.node.common.int
import com.altnautica.gcs.feature.node.common.long
import com.altnautica.gcs.feature.node.common.obj
import com.altnautica.gcs.feature.node.common.objects
import com.altnautica.gcs.feature.node.common.str
import com.altnautica.gcs.feature.node.common.strings
import kotlinx.serialization.json.JsonObject

/** One fleet slot on the ground station: a drone it carries (`GET /api/wfb/pair` `slots[]`). */
data class CarriedSlot(
    val slot: Int,
    val deviceId: String,
    val pairedAtMs: Long?,
    val systemIdConflict: Boolean,
    val videoHero: Boolean,
    val relayCredential: String?,
)

/** A carried drone's relayed status row (`GET .../relayed/status` `peers[]`). */
data class RelayedPeer(
    val deviceId: String,
    val name: String?,
    val agentVersion: String?,
    val statusFresh: Boolean,
    val statusAgeS: Double?,
)

data class CarriedDrones(
    val slots: List<CarriedSlot>,
    /** Null when the station publishes no relayed status. */
    val peers: List<RelayedPeer>?,
    val primaryDroneId: String?,
) {
    companion object {
        fun parse(pair: JsonObject?, relayed: JsonObject?, status: JsonObject?): CarriedDrones = CarriedDrones(
            slots = pair?.arr("slots")?.objects().orEmpty().mapNotNull { s ->
                val id = s.str("device_id") ?: return@mapNotNull null
                CarriedSlot(
                    slot = s.int("slot") ?: 0,
                    deviceId = id,
                    pairedAtMs = s.long("paired_at_ms"),
                    systemIdConflict = s.bool("system_id_conflict") == true,
                    videoHero = s.bool("video_hero") == true,
                    relayCredential = s.str("relay_credential"),
                )
            }.sortedBy { it.slot },
            peers = relayed?.arr("peers")?.objects()?.mapNotNull { p ->
                val id = p.str("device_id") ?: return@mapNotNull null
                RelayedPeer(id, p.str("name"), p.str("agent_version"), p.bool("status_fresh") == true, p.double("status_age_s"))
            },
            primaryDroneId = status?.obj("paired_drone")?.str("device_id"),
        )
    }
}

/** The radio block of the ground station `/status` (`link`) plus its `/wfb` read. */
data class RadioLink(
    val state: String?,
    val diagnosis: String?,
    val rssiDbm: Double?,
    val snrDb: Double?,
    val noiseDbm: Double?,
    val bitrateMbps: Double?,
    val fecRecovered: Long?,
    val fecLost: Long?,
    val packetsReceived: Long?,
    val packetsLost: Long?,
    val lossPercent: Double?,
    val channel: Int?,
    val txPowerDbm: Double?,
    val mcsIndex: Int?,
    val role: String?,
) {
    companion object {
        fun parse(status: JsonObject, wfb: JsonObject?): RadioLink {
            val link = status.obj("link") ?: JsonObject(emptyMap())
            return RadioLink(
                state = link.str("state"),
                diagnosis = link.str("link_diag"),
                rssiDbm = link.double("rssi_dbm"),
                snrDb = link.double("snr_db"),
                noiseDbm = link.double("noise_dbm"),
                bitrateMbps = link.double("bitrate_mbps"),
                fecRecovered = link.long("fec_recovered"),
                fecLost = link.long("fec_lost"),
                packetsReceived = link.long("packets_received"),
                packetsLost = link.long("packets_lost"),
                lossPercent = link.double("loss_percent"),
                channel = wfb?.int("channel") ?: link.int("channel"),
                txPowerDbm = wfb?.double("tx_power_dbm") ?: link.double("tx_power_dbm"),
                mcsIndex = link.int("mcs_index"),
                role = status.obj("role")?.str("current"),
            )
        }
    }
}

data class AccessPoint(
    val enabled: Boolean?,
    val running: Boolean?,
    val ssid: String?,
    val channel: Int?,
    val clients: Int,
    val standingDown: Boolean,
)

data class WifiClient(val connected: Boolean?, val ssid: String?, val signal: Int?, val ip: String?)

data class Modem(val enabled: Boolean?, val apn: String?, val capMb: Int?, val dataUsedMb: Int?, val state: String?)

data class StationNetwork(
    val ap: AccessPoint?,
    val client: WifiClient?,
    val ethernetLink: Boolean?,
    val ethernetIp: String?,
    val modem: Modem?,
    val activeUplink: String?,
    val priority: List<String>,
    val shareUplink: Boolean?,
) {
    companion object {
        fun parse(o: JsonObject): StationNetwork = StationNetwork(
            ap = o.obj("ap")?.let { ap ->
                AccessPoint(
                    enabled = ap.bool("enabled"),
                    running = ap.bool("running"),
                    ssid = ap.str("ssid"),
                    channel = ap.int("channel"),
                    clients = ap.arr("connected_clients")?.size ?: 0,
                    standingDown = ap.bool("standing_down") == true,
                )
            },
            client = o.obj("wifi_client")?.let { c -> WifiClient(c.bool("connected"), c.str("ssid"), c.int("signal"), c.str("ip")) },
            ethernetLink = o.obj("ethernet")?.bool("link"),
            ethernetIp = o.obj("ethernet")?.str("ip"),
            modem = o.obj("modem_4g")?.let { m -> Modem(m.bool("enabled"), m.str("apn"), m.int("cap_mb"), m.int("data_used_mb"), m.str("state")) },
            activeUplink = o.str("active_uplink"),
            priority = o.arr("priority")?.strings().orEmpty(),
            shareUplink = o.bool("share_uplink"),
        )
    }
}

/** One row of `GET .../network/client/scan`. */
data class ScannedNetwork(val ssid: String, val signalDbm: Int?, val channel: Int?, val security: String?) {
    val open: Boolean get() = security.isNullOrBlank() || security.equals("open", ignoreCase = true) || security == "--"

    companion object {
        fun parse(o: JsonObject): List<ScannedNetwork> = o.arr("networks")?.objects().orEmpty()
            .mapNotNull { n -> n.str("ssid")?.takeIf { it.isNotBlank() }?.let { ScannedNetwork(it, n.int("signal"), n.int("channel"), n.str("security")) } }
            // Strongest access point first, then one row per network name.
            .sortedByDescending { it.signalDbm ?: Int.MIN_VALUE }
            .distinctBy { it.ssid }
    }
}

data class MeshNeighbor(val mac: String, val tq: Int?, val lastSeenMs: Long?)

data class MeshGateway(val mac: String, val tq: Int?, val selected: Boolean)

data class MeshState(
    val role: String?,
    val configuredRole: String?,
    val supportedRoles: List<String>,
    /** Null on a direct node (`E_NOT_IN_MESH`) or when no snapshot is current. */
    val up: Boolean?,
    val partition: Boolean?,
    val meshId: String?,
    val neighbors: List<MeshNeighbor>,
    val gateways: List<MeshGateway>,
) {
    companion object {
        fun parse(role: JsonObject, mesh: JsonObject?): MeshState = MeshState(
            role = role.str("role") ?: role.str("current"),
            configuredRole = role.str("configured"),
            supportedRoles = role.arr("supported")?.strings().orEmpty(),
            up = mesh?.bool("up"),
            partition = mesh?.bool("partition"),
            meshId = mesh?.str("mesh_id"),
            neighbors = mesh?.arr("neighbors")?.objects().orEmpty().mapNotNull { n ->
                n.str("mac")?.let { MeshNeighbor(it, n.int("tq"), n.long("last_seen_ms")) }
            },
            gateways = mesh?.arr("gateways")?.objects().orEmpty().mapNotNull { g ->
                g.str("mac")?.let { MeshGateway(it, g.int("tq"), g.bool("selected") == true) }
            },
        )
    }
}

/** The receiver's pairing window (`GET .../pair/pending`). */
data class PairWindow(
    val open: Boolean,
    val closesAtMs: Long?,
    val code: String?,
    val pending: List<String>,
    val approved: List<String>,
) {
    companion object {
        fun parse(o: JsonObject): PairWindow = PairWindow(
            open = o.bool("open") == true,
            closesAtMs = o.long("closes_at_ms"),
            code = o.str("code"),
            pending = o.arr("pending")?.objects().orEmpty().mapNotNull { it.str("device_id") },
            approved = o.obj("approvals")?.keys?.toList().orEmpty(),
        )
    }
}
