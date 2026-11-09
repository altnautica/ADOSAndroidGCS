package com.altnautica.gcs.core.link.mission

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The flight controller's actual plan as the vehicle's agent mirrors it
 * (`GET /api/v1/mission`). Emits null while the vehicle is reached straight over
 * MAVLink or the agent cannot be read. The planner and the cockpit maps both read it.
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
