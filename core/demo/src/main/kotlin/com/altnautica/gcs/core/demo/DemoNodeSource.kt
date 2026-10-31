package com.altnautica.gcs.core.demo

import com.altnautica.gcs.core.link.vehicle.LinkActivity
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Publishes the demo drone, Visor and ground station while demo mode is on; always live. */
@Singleton
class DemoNodeSource @Inject constructor(
    settings: DemoSettings,
    activity: LinkActivity,
) : NodeSource {

    override val nodes: Flow<List<NodeSummary>> = combine(settings.enabled, activity.lastHeartbeat) { on, heartbeats ->
        if (!on) {
            emptyList()
        } else {
            listOf(
                drone(heartbeats[DemoIds.DRONE]),
                agentNode(DemoIds.VISOR, DemoIds.VISOR_NAME, NodeProfile.Visor),
                agentNode(DemoIds.GROUND, DemoIds.GROUND_NAME, NodeProfile.GroundStation),
            )
        }
    }.distinctUntilChanged()

    private fun drone(lastSeen: Long?) = NodeSummary(
        id = DemoIds.DRONE,
        name = DemoIds.DRONE_NAME,
        profile = NodeProfile.Drone,
        reaches = listOf(Reach.DirectFc),
        liveness = Liveness.Live,
        agentVersion = null,
        fcVariant = FcVariant.MavlinkArdupilot,
        carriedDroneId = null,
        lastSeenMs = lastSeen,
    )

    /** A demo node with an agent-like profile; it lists a LAN reach so its agent pages show. */
    private fun agentNode(id: DeviceId, name: String, profile: NodeProfile) = NodeSummary(
        id = id,
        name = name,
        profile = profile,
        reaches = listOf(Reach.Lan),
        liveness = Liveness.Live,
        agentVersion = null,
        fcVariant = FcVariant.Unknown,
        carriedDroneId = null,
        lastSeenMs = null,
    )
}
