package com.altnautica.gcs.replay

import com.altnautica.gcs.core.link.vehicle.ReplayVehicles
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.feature.logs.tlog.TlogReplayTransport
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Telemetry-log replays as nodes, so the cockpit can open one like any other
 * node. A replay is receive-only and lives only while its cockpit is open.
 */
@Singleton
class ReplayNodeSource @Inject constructor(private val replays: ReplayVehicles) : NodeSource {
    override val nodes: Flow<List<NodeSummary>> = replays.active.map { active ->
        active.keys.map { id ->
            NodeSummary(
                id = id,
                name = id.value.removePrefix(PREFIX),
                profile = NodeProfile.Drone,
                reaches = listOf(Reach.Blocked(BlockReason.ReceiveOnly)),
                liveness = Liveness.Live,
                agentVersion = null,
                fcVariant = FcVariant.Unknown,
                carriedDroneId = null,
                lastSeenMs = null,
            )
        }
    }

    companion object {
        const val PREFIX = "replay:"

        fun isReplay(id: String): Boolean = id.startsWith(PREFIX)
    }
}

/** Opens and closes log replays for the cockpit. */
@Singleton
class ReplayLauncher @Inject constructor(private val replays: ReplayVehicles) {

    /** Starts replaying the telemetry log at [tlogPath]; returns the replay node id. */
    suspend fun open(tlogPath: String): DeviceId {
        val file = File(tlogPath)
        val id = DeviceId(ReplayNodeSource.PREFIX + file.nameWithoutExtension)
        replays.close(id)
        replays.open(id, TlogReplayTransport(file))
        return id
    }

    suspend fun close(id: DeviceId) = replays.close(id)
}
