package com.altnautica.gcs.core.link.follow

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import javax.inject.Inject

/**
 * How many follow beacon fixes the vehicle's router has published to its plugin bus, the
 * `follow_beacon.published` count in its telemetry. The cockpit says "Sharing" only while this
 * rises, so a fix the router drops never reads as shared.
 */
interface RouterBeaconStatus {
    suspend fun published(vehicle: DeviceId, reach: Reach): Long?
}

/** [RouterBeaconStatus] from the vehicle agent's `/api/telemetry`, directly or through its ground station. */
class AgentRouterBeaconStatus @Inject constructor(
    private val clients: AgentClientFactory,
) : RouterBeaconStatus {
    override suspend fun published(vehicle: DeviceId, reach: Reach): Long? {
        val client = when (reach) {
            Reach.Lan -> clients.forNode(vehicle)
            is Reach.ViaGround -> clients.forCarried(reach.groundId, vehicle)
            else -> null
        } ?: return null
        return (client.telemetry() as? AgentResult.Ok)?.value?.followBeacon?.published
    }
}
