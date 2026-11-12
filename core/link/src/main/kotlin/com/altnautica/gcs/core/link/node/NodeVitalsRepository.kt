package com.altnautica.gcs.core.link.node

import android.os.SystemClock
import com.altnautica.gcs.core.agent.AgentClientFactory
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
    val gpsFix: com.altnautica.gcs.core.model.GpsFix? = null,
    val satellites: Int? = null,
    /** When a direct link last heard the vehicle (`elapsedRealtime`); null for agent-reported vitals. */
    val heardAtMs: Long? = null,
) {
    companion object {
        val UNKNOWN = NodeVitals(null, null, null)

        /** MAVLink `GPS_FIX_TYPE` to [com.altnautica.gcs.core.model.GpsFix]; null when unknown. */
        fun fixOf(fixType: Int?): com.altnautica.gcs.core.model.GpsFix? = when (fixType) {
            0 -> com.altnautica.gcs.core.model.GpsFix.NoGps
            1 -> com.altnautica.gcs.core.model.GpsFix.NoFix
            2 -> com.altnautica.gcs.core.model.GpsFix.Fix2D
            3 -> com.altnautica.gcs.core.model.GpsFix.Fix3D
            4 -> com.altnautica.gcs.core.model.GpsFix.DGps
            5 -> com.altnautica.gcs.core.model.GpsFix.RtkFloat
            6 -> com.altnautica.gcs.core.model.GpsFix.RtkFixed
            else -> null
        }
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
                    gpsFix = NodeVitals.fixOf(t?.gps?.fixType),
                    satellites = t?.gps?.satellites?.takeIf { it >= 0 },
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
     * it is recent. No success yet, or none for the poll's [NodePoll.maxAgeMs]
     * (longer through a relay), is unknown, and a quiet poller (node forgotten)
     * still turns unknown on time.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun fresh(polls: Flow<NodePoll>, read: (NodePoll) -> NodeVitals): Flow<NodeVitals> =
        polls.transformLatest { poll ->
            val age = poll.lastOkMs?.let { clock() - it }
            if (age == null || age >= poll.maxAgeMs) {
                emit(NodeVitals.UNKNOWN)
            } else {
                emit(read(poll))
                delay(poll.maxAgeMs - age)
                emit(NodeVitals.UNKNOWN)
            }
        }

    /** Vitals of [id]'s link while it is open; unknown otherwise. Never keeps the link open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun openVehicleVitals(id: DeviceId): Flow<NodeVitals> = vehicles.openVehicles
        .map { it[id] }
        .distinctUntilChanged()
        .flatMapLatest { v -> if (v == null) flowOf(NodeVitals.UNKNOWN) else telemetryVitals(v) }

    /**
     * The link's last values, shown only while heartbeats are recent: none yet, or none for
     * [MAX_HEARTBEAT_AGE_MS], is unknown (a silent link keeps its last values in the flows).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun telemetryVitals(v: Vehicle): Flow<NodeVitals> =
        combine(v.telemetry.battery, v.telemetry.heartbeat, v.telemetry.gps) { b, hb, gps ->
            hb?.tMs to NodeVitals(
                batteryPct = b?.remainingPct?.takeIf { it >= 0 },
                modeName = hb?.mode?.name,
                armed = hb?.armed,
                gpsFix = gps?.fix,
                satellites = gps?.satellites,
                heardAtMs = hb?.tMs,
            )
        }.transformLatest { (heardMs, vitals) ->
            val age = heardMs?.let { clock() - it }
            if (age == null || age >= MAX_HEARTBEAT_AGE_MS) {
                emit(NodeVitals.UNKNOWN)
            } else {
                emit(vitals)
                delay(MAX_HEARTBEAT_AGE_MS - age)
                emit(NodeVitals.UNKNOWN)
            }
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
        private const val SNAPSHOT_PATH = "api/video/snapshot"

        /** Oldest direct-link heartbeat still shown as current (the link turns stale at the same age). */
        const val MAX_HEARTBEAT_AGE_MS = 5_000L
        private const val THUMBNAIL_INTERVAL_MS = 15_000L
    }
}
