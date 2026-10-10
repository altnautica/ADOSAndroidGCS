package com.altnautica.gcs.core.agent.node

import com.altnautica.gcs.core.agent.discovery.DiscoveredAgent
import com.altnautica.gcs.core.agent.groundstation.PairedDrone
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach

/** The latest status poll of one paired node. */
data class NodePoll(
    val status: StatusFull?,
    /** `elapsedRealtime` of the last successful poll, null when none succeeded. */
    val lastOkMs: Long?,
    /** The drone a ground station reports as paired to it. */
    val carriedDroneId: String?,
    val agentVersion: String?,
    /** A ground station's summary of that drone (mode, battery), polled with it; null elsewhere. */
    val carriedDrone: PairedDrone? = null,
)

/** Pure merge of stored credentials, discovery, and status polls into [NodeSummary]s. */
object LanNodeAssembler {

    const val LIVE_MS = 45_000L
    const val STALE_MS = 60_000L

    fun assemble(
        stored: List<NodeCredential>,
        discovered: List<DiscoveredAgent>,
        polls: Map<String, NodePoll>,
        nowMs: Long,
    ): List<NodeSummary> {
        val out = LinkedHashMap<String, NodeSummary>()
        val discoveredById = discovered.filter { it.deviceId != null }.associateBy { it.deviceId!! }

        for (cred in stored) {
            val poll = polls[cred.deviceId]
            val txt = discoveredById[cred.deviceId]
            out[cred.deviceId] = NodeSummary(
                id = cred.id,
                name = cred.name ?: txt?.name ?: cred.deviceId,
                profile = profile(poll?.status?.profile ?: txt?.profile ?: cred.profile),
                reaches = listOf(Reach.Lan),
                liveness = liveness(poll?.lastOkMs, nowMs),
                agentVersion = poll?.agentVersion ?: poll?.status?.version ?: txt?.version,
                fcVariant = fcVariant(poll?.status),
                carriedDroneId = poll?.carriedDroneId?.let(::DeviceId),
                lastSeenMs = poll?.lastOkMs,
            )
        }

        // Drones carried by a reachable ground station gain a relayed reach.
        for (cred in stored) {
            val carried = polls[cred.deviceId]?.carriedDroneId ?: continue
            val ground = out[cred.deviceId] ?: continue
            if (ground.profile != NodeProfile.GroundStation) continue
            val via = Reach.ViaGround(ground.id)
            val existing = out[carried]
            out[carried] = existing?.copy(reaches = (existing.reaches + via).distinct())
                ?: NodeSummary(
                    id = DeviceId(carried),
                    name = carried,
                    profile = NodeProfile.Drone,
                    reaches = listOf(via),
                    liveness = ground.liveness,
                    agentVersion = null,
                    fcVariant = FcVariant.Unknown,
                    carriedDroneId = null,
                    lastSeenMs = ground.lastSeenMs,
                )
        }

        // Nodes on the LAN this install holds no key for.
        for (agent in discovered) {
            val id = agent.deviceId ?: continue
            if (id in out) continue
            out[id] = NodeSummary(
                id = DeviceId(id),
                name = agent.name ?: id,
                profile = profile(agent.profile),
                reaches = listOf(Reach.Blocked(BlockReason.NotPaired)),
                liveness = Liveness.Live,
                agentVersion = agent.version,
                fcVariant = FcVariant.Unknown,
                carriedDroneId = null,
                lastSeenMs = agent.lastSeenMs,
            )
        }
        return out.values.toList()
    }

    fun liveness(lastOkMs: Long?, nowMs: Long): Liveness {
        if (lastOkMs == null) return Liveness.Offline
        val age = nowMs - lastOkMs
        return when {
            age < LIVE_MS -> Liveness.Live
            age < STALE_MS -> Liveness.Stale
            else -> Liveness.Offline
        }
    }

    fun profile(value: String?): NodeProfile = when (value?.lowercase()?.replace('_', '-')) {
        "drone" -> NodeProfile.Drone
        "ground-station" -> NodeProfile.GroundStation
        "workstation" -> NodeProfile.Workstation
        else -> NodeProfile.Unknown
    }

    fun fcVariant(status: StatusFull?): FcVariant {
        val firmware = status?.fcFirmware?.lowercase()
        val variant = status?.fcVariant?.lowercase()
        return when {
            firmware == "ardupilot" -> FcVariant.MavlinkArdupilot
            firmware == "px4" -> FcVariant.MavlinkPx4
            firmware in MSP_FIRMWARE || variant in MSP_FIRMWARE -> FcVariant.Msp
            else -> FcVariant.Unknown
        }
    }

    private val MSP_FIRMWARE = setOf("betaflight", "inav", "msp")
}
