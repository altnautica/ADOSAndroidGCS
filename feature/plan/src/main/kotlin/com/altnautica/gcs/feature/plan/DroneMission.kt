package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.mission.MirrorItem
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.MissionWire
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The flight controller's actual plan as the vehicle's agent mirrors it
 * (`GET /api/v1/mission`). Emits null while the vehicle is reached straight over
 * MAVLink or the agent cannot be read.
 */
fun interface MissionMirrorSource {
    fun observe(id: DeviceId): Flow<MissionMirror?>
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AgentMissionMirrorSource @Inject constructor(
    private val nodes: NodeRepository,
    private val clients: AgentClientFactory,
) : MissionMirrorSource {
    override fun observe(id: DeviceId): Flow<MissionMirror?> = nodes.node(id)
        .map { it?.reaches?.firstOrNull() }
        .distinctUntilChanged()
        .flatMapLatest<Reach?, MissionMirror?> { reach ->
            val client = when (reach) {
                Reach.Lan -> clients.forNode(id)
                is Reach.ViaGround -> clients.forCarried(reach.groundId, id)
                else -> null
            } ?: return@flatMapLatest flowOf<MissionMirror?>(null)
            flow<MissionMirror?> {
                while (true) {
                    emit(client.mission.mirror().getOrNull())
                    delay(POLL_MS)
                }
            }
        }
        .distinctUntilChanged()

    companion object {
        const val POLL_MS = 2_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MissionMirrorModule {
    @Binds
    abstract fun bindMissionMirrorSource(impl: AgentMissionMirrorSource): MissionMirrorSource
}

/**
 * What this device knows of the plan it put on the vehicle: the plan id the vehicle
 * returned, the wire item count, and the agent's upload counter seen right after.
 */
data class PlanReceipt(val opaqueId: Long, val wireCount: Int, val uploadsSeen: Long?)

/** The plan-identity rule between this device's plan and the vehicle's. */
object PlanIdentity {
    /**
     * True when the vehicle still holds the plan of [receipt]: a non-zero vehicle plan id
     * equal to the one returned on upload, or, without plan ids, the same item count with
     * no other client's upload seen since.
     */
    fun matches(mirror: MissionMirror, receipt: PlanReceipt?): Boolean {
        if (receipt == null) return false
        val id = mirror.mission?.id ?: 0L
        if (id != 0L) return id == receipt.opaqueId
        if (mirror.count != receipt.wireCount) return false
        return receipt.uploadsSeen == null || receipt.uploadsSeen == mirror.uploadsSeen
    }

    /** The receipt for a plan read from [mirror] itself (Load from drone). */
    fun receiptOf(mirror: MissionMirror) = PlanReceipt(mirror.mission?.id ?: 0L, mirror.count, mirror.uploadsSeen)

    /** The mirrored mission as a plan; null when the vehicle holds no mission items. */
    fun planOf(mirror: MissionMirror, nextId: () -> Long): MissionPlan? {
        val wire = mirror.items.filter { it.missionType == 0 }.sortedBy { it.seq }.map(::toItem)
        val home = if (mirror.homeSlot) wire.firstOrNull() else null
        val items = if (mirror.homeSlot) wire.drop(1) else wire
        if (items.isEmpty()) return null
        return MissionWire.fromWire(home, items, emptyList(), emptyList(), mirror.homeSlot, nextId).plan
    }

    private fun toItem(m: MirrorItem) = MissionItem(
        command = m.command,
        frame = m.frame,
        param1 = m.param1,
        param2 = m.param2,
        param3 = m.param3,
        param4 = m.param4,
        x = m.x,
        y = m.y,
        z = m.z,
        autocontinue = m.autocontinue != 0,
    )
}

/** The vehicle's plan as the planner shows it. [differs]: it is not this device's plan. */
data class DroneMissionUi(
    val plan: MissionPlan?,
    val state: MissionState,
    val differs: Boolean,
)
