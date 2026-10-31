package com.altnautica.gcs.core.link.direct

import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.vehicle.LinkActivity
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Nodes reachable without an agent: USB-attached flight controllers and manual
 * UDP/TCP endpoints. USB devices without permission are listed as blocked until
 * the operator grants it.
 *
 * Liveness of USB and network endpoints follows the vehicle's heartbeat while
 * a link is open (live within 5 s, stale within 15 s); an endpoint nobody has
 * opened yet is offline.
 */
@Singleton
class DirectLinkSource @Inject constructor(
    private val settings: DirectLinkSettings,
    private val usb: UsbFlightControllers,
    private val activity: LinkActivity,
    private val clock: ElapsedClock,
) : NodeSource {

    private val ticker: Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(LIVENESS_TICK_MS)
        }
    }

    override val nodes: Flow<List<NodeSummary>> = combine(
        settings.endpoints,
        usb.devices,
        activity.lastHeartbeat,
        ticker,
    ) { endpoints, devices, heartbeats, _ ->
        buildList {
            devices.forEach { d ->
                add(
                    node(
                        id = d.nodeId,
                        name = d.label,
                        reaches = if (d.hasPermission) listOf(Reach.DirectFc) else listOf(Reach.Blocked(BlockReason.NoGrant)),
                        lastSeen = heartbeats[d.nodeId],
                        attached = true,
                    ),
                )
            }
            endpoints.forEach { e ->
                add(node(e.nodeId, e.label, listOf(Reach.DirectFc), heartbeats[e.nodeId], attached = false))
            }
        }
    }.distinctUntilChanged()

    /** Asks the system for permission to use an attached USB device. */
    fun requestUsbPermission(device: UsbFlightController) = usb.requestPermission(device.deviceName)

    private fun node(id: DeviceId, name: String, reaches: List<Reach>, lastSeen: Long?, attached: Boolean): NodeSummary {
        val age = lastSeen?.let { clock.nowMs() - it }
        val liveness = when {
            age != null && age < LIVE_MS -> Liveness.Live
            age != null && age < STALE_MS -> Liveness.Stale
            attached -> Liveness.Stale
            else -> Liveness.Offline
        }
        return NodeSummary(
            id = id,
            name = name,
            profile = NodeProfile.Drone,
            reaches = reaches,
            liveness = liveness,
            agentVersion = null,
            fcVariant = activity.variant(id),
            carriedDroneId = null,
            lastSeenMs = lastSeen,
        )
    }

    private companion object {
        const val LIVENESS_TICK_MS = 1_000L
        const val LIVE_MS = 5_000L
        const val STALE_MS = 15_000L
    }
}
