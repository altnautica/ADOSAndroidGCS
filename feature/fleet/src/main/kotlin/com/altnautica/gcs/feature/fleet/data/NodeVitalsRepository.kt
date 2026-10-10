package com.altnautica.gcs.feature.fleet.data

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.node.AgentStatusRepository
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The few live values a fleet card or node header shows. Null means unknown, never zero. */
data class NodeVitals(
    val batteryPct: Int?,
    val modeName: String?,
    val armed: Boolean?,
) {
    companion object {
        val UNKNOWN = NodeVitals(null, null, null)
    }
}

/**
 * Per-node vitals without opening or holding a link the operator did not ask
 * for: agent nodes read the shared status poll; direct links read a vehicle
 * only while some other observer (the cockpit) already holds it open.
 */
@Singleton
class NodeVitalsRepository @Inject constructor(
    private val agentStatus: AgentStatusRepository,
    private val vehicles: VehicleRepository,
    private val clients: AgentClientFactory,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun vitals(node: NodeSummary): Flow<NodeVitals> = when {
        node.reaches.any { it == Reach.Lan || it is Reach.ViaGround } -> agentStatus.observe(node.id).map { poll ->
            val t = poll.status?.telemetry
            NodeVitals(
                batteryPct = t?.battery?.remaining?.takeIf { it >= 0 },
                modeName = t?.mode?.takeIf { it.isNotBlank() },
                armed = t?.armed,
            )
        }
        node.reaches.firstOrNull() == Reach.DirectFc -> openVehicleVitals(node.id)
        else -> flowOf(NodeVitals.UNKNOWN)
    }.distinctUntilChanged()

    /** Vitals of [id]'s link while it is open; unknown otherwise. Never keeps the link open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openVehicleVitals(id: DeviceId): Flow<NodeVitals> = vehicles.openVehicles
        .map { it[id] }
        .distinctUntilChanged()
        .flatMapLatest { v -> if (v == null) flowOf(NodeVitals.UNKNOWN) else telemetryVitals(v) }

    private fun telemetryVitals(v: Vehicle): Flow<NodeVitals> =
        combine(v.telemetry.battery, v.telemetry.heartbeat) { b, hb ->
            NodeVitals(
                batteryPct = b?.remainingPct?.takeIf { it >= 0 },
                modeName = hb?.mode?.name,
                armed = hb?.armed,
            )
        }

    /**
     * JPEG stills from the agent's primary stream, one every [intervalMs] while
     * collected. Emits null when the agent has no frame to give.
     */
    fun thumbnails(id: DeviceId, intervalMs: Long = THUMBNAIL_INTERVAL_MS): Flow<ByteArray?> = flow {
        while (true) {
            val client = clients.forNode(id)
            val frame = when (val r = client?.bytes(SNAPSHOT_PATH)) {
                is AgentResult.Ok -> r.value.takeIf { it.isSuccessful && it.bytes.isNotEmpty() }?.bytes
                else -> null
            }
            emit(frame)
            delay(intervalMs)
        }
    }

    private companion object {
        const val SNAPSHOT_PATH = "api/video/snapshot"
        const val THUMBNAIL_INTERVAL_MS = 15_000L
    }
}
