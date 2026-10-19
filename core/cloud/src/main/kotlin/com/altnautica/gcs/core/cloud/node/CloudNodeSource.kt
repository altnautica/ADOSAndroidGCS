package com.altnautica.gcs.core.cloud.node

import com.altnautica.gcs.core.cloud.WallClock
import com.altnautica.gcs.core.cloud.auth.CloudAuthState
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import com.altnautica.gcs.core.cloud.convex.CloudJson
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import com.altnautica.gcs.core.cloud.grant.GrantSource
import com.altnautica.gcs.core.cloud.grant.GrantState
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class CloudDroneRow(
    val deviceId: String,
    val name: String = "",
    val agentVersion: String? = null,
    val lastSeen: Double? = null,
    val profile: String? = null,
)

@Serializable
internal data class CloudStatusRow(
    val updatedAt: Double? = null,
    val profile: String? = null,
    val fcVariant: String? = null,
    val version: String? = null,
)

@Serializable
internal data class CloudStatusEntry(val drone: CloudDroneRow, val status: CloudStatusRow? = null)

/** Live under 45 s since the last heartbeat, stale under 60 s, offline after. */
object CloudLiveness {
    const val LIVE_MS = 45_000L
    const val STALE_MS = 60_000L

    fun of(lastSeenMs: Long?, nowMs: Long): Liveness {
        if (lastSeenMs == null) return Liveness.Offline
        val age = nowMs - lastSeenMs
        return when {
            age < LIVE_MS -> Liveness.Live
            age < STALE_MS -> Liveness.Stale
            else -> Liveness.Offline
        }
    }
}

/**
 * The operator's cloud-paired nodes. Empty unless the cloud is enabled and
 * signed in. Liveness is re-evaluated every second; a node whose devices the
 * held broker grant covers is reachable live, else through the command queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CloudNodeSource @Inject constructor(
    private val gateway: ConvexGateway,
    private val auth: CloudAuthState,
    private val grants: GrantSource,
    private val clock: WallClock,
) : NodeSource {

    override val nodes: Flow<List<NodeSummary>> =
        combine(auth.active, auth.state) { on, s -> on && s == CloudSessionState.SignedIn }
            .distinctUntilChanged()
            .flatMapLatest { on -> if (on) live() else flowOf(emptyList()) }

    private fun live(): Flow<List<NodeSummary>> {
        val drones = gateway.subscribe("cmdDrones:listMyDrones").map { r -> r.map(::decodeDrones).getOrNull() }
        val statuses = gateway.subscribe("cmdDroneStatus:listMyCloudStatuses").map { r -> r.map(::decodeStatuses).getOrNull() }
        val tick = flow {
            while (true) {
                emit(clock.nowMs())
                delay(TICK_MS)
            }
        }
        return combine(
            drones.onStart { emit(null) },
            statuses.onStart { emit(null) },
            grants.state,
            tick,
        ) { d, s, g, now -> summarize(d.orEmpty(), s.orEmpty(), g, now) }
            .distinctUntilChanged()
    }

    companion object {
        private const val TICK_MS = 1_000L

        internal fun decodeDrones(json: JsonElement): List<CloudDroneRow> =
            CloudJson.decodeFromJsonElement(ListSerializer(CloudDroneRow.serializer()), json)

        internal fun decodeStatuses(json: JsonElement): List<CloudStatusEntry> =
            CloudJson.decodeFromJsonElement(ListSerializer(CloudStatusEntry.serializer()), json)

        internal fun summarize(
            drones: List<CloudDroneRow>,
            statuses: List<CloudStatusEntry>,
            grant: GrantState,
            nowMs: Long,
        ): List<NodeSummary> {
            val statusById = statuses.associateBy { it.drone.deviceId }
            val rows = drones.ifEmpty { statuses.map { it.drone } }
            return rows.filter { it.deviceId.isNotBlank() }.map { d ->
                val st = statusById[d.deviceId]?.status
                val lastSeen = listOfNotNull(st?.updatedAt, d.lastSeen).maxOrNull()?.toLong()
                val liveness = CloudLiveness.of(lastSeen, nowMs)
                val held = grant.grant
                val covered = held != null && d.deviceId in held.deviceIds && held.expiresAtMs > nowMs
                val reaches = if (covered && liveness != Liveness.Offline) {
                    listOf(Reach.CloudLive, Reach.CloudQueue)
                } else {
                    listOf(Reach.CloudQueue)
                }
                NodeSummary(
                    id = DeviceId(d.deviceId),
                    name = d.name.ifBlank { d.deviceId },
                    profile = profileOf(st?.profile ?: d.profile),
                    reaches = reaches,
                    liveness = liveness,
                    agentVersion = d.agentVersion ?: st?.version,
                    fcVariant = variantOf(st?.fcVariant),
                    carriedDroneId = null,
                    lastSeenMs = lastSeen,
                )
            }
        }

        fun profileOf(raw: String?): NodeProfile = when (raw?.trim()?.lowercase()) {
            "drone" -> NodeProfile.Drone
            "ground-station", "ground_station", "groundstation" -> NodeProfile.GroundStation
            "workstation" -> NodeProfile.Workstation
            "visor" -> NodeProfile.Visor
            else -> NodeProfile.Unknown
        }

        fun variantOf(raw: String?): FcVariant = when (raw?.trim()?.lowercase()) {
            "ardupilot" -> FcVariant.MavlinkArdupilot
            "px4" -> FcVariant.MavlinkPx4
            "betaflight", "inav", "msp" -> FcVariant.Msp
            else -> FcVariant.Unknown
        }
    }
}
