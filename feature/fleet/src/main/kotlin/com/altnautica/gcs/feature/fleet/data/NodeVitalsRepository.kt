package com.altnautica.gcs.feature.fleet.data

import android.os.SystemClock
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.node.AgentStatusRepository
import com.altnautica.gcs.core.agent.node.NodePoll
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
import kotlinx.coroutines.flow.transformLatest
import kotlin.math.roundToInt

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
 * for: agent nodes read the shared status poll (a drone known only through
 * its ground station reads the station's summary of it); direct links read a
 * vehicle only while some other observer (the cockpit) already holds it open.
 */
@Singleton
class NodeVitalsRepository @Inject constructor(
    private val agentStatus: AgentStatusRepository,
    private val vehicles: VehicleRepository,
    private val clients: AgentClientFactory,
) {

    /** `elapsedRealtime`, the clock [AgentStatusRepository] stamps `lastOkMs` with. */
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    fun vitals(node: NodeSummary): Flow<NodeVitals> {
        val ground = node.reaches.firstNotNullOfOrNull { it as? Reach.ViaGround }
        return when {
            Reach.Lan in node.reaches -> fresh(agentStatus.observe(node.id)) { poll ->
                val t = poll.status?.telemetry
                NodeVitals(
                    batteryPct = t?.battery?.remaining?.takeIf { it in 0..100 },
                    modeName = t?.mode?.takeIf { it.isNotBlank() },
                    armed = t?.armed,
                )
            }
            ground != null -> fresh(agentStatus.observe(ground.groundId)) { poll ->
                val carried = poll.carriedDrone?.takeIf { it.deviceId == node.id.value }
                NodeVitals(
                    batteryPct = carried?.batteryPct?.takeIf { it in 0f..100f }?.roundToInt(),
                    modeName = carried?.fcMode?.takeIf { it.isNotBlank() },
                    armed = null,
                )
            }
            node.reaches.firstOrNull() == Reach.DirectFc -> openVehicleVitals(node.id)
            else -> flowOf(NodeVitals.UNKNOWN)
        }.distinctUntilChanged()
    }

    /**
     * The poll keeps its last good status through failures; show it only while
     * it is recent. No success yet, or none for [MAX_POLL_AGE_MS], is unknown,
     * and a quiet poller (node forgotten) still turns unknown on time.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fresh(polls: Flow<NodePoll>, read: (NodePoll) -> NodeVitals): Flow<NodeVitals> =
        polls.transformLatest { poll ->
            val age = poll.lastOkMs?.let { clock() - it }
            if (age == null || age >= MAX_POLL_AGE_MS) {
                emit(NodeVitals.UNKNOWN)
            } else {
                emit(read(poll))
                delay(MAX_POLL_AGE_MS - age)
                emit(NodeVitals.UNKNOWN)
            }
        }

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

    companion object {
        /**
         * Oldest poll still shown: two status intervals plus one request
         * timeout, so a single slow or failed poll does not blank the card.
         */
        const val MAX_POLL_AGE_MS = 2 * AgentStatusRepository.LAN_INTERVAL_MS + AgentHttp.LAN_TIMEOUT_S * 1_000L
        private const val SNAPSHOT_PATH = "api/video/snapshot"
        private const val THUMBNAIL_INTERVAL_MS = 15_000L
    }
}
