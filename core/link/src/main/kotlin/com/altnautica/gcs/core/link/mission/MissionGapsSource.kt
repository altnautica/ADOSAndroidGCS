package com.altnautica.gcs.core.link.mission

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.MissionGap
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
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The waypoints the vehicle's pilot skipped this flight (its `pilot` state's `mission.gaps`),
 * read from the agent's navigation status (`GET /api/v1/nav/status`) or an in-process node's.
 * Empty with no agent, no pilot or no gap. The planner marks them on its map.
 */
fun interface MissionGapsSource {
    fun observe(id: DeviceId): Flow<List<MissionGap>>
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AgentMissionGapsSource @Inject constructor(
    private val nodes: NodeRepository,
    private val clients: AgentClientFactory,
    private val local: Set<@JvmSuppressWildcards LocalNodeApis>,
) : MissionGapsSource {
    override fun observe(id: DeviceId): Flow<List<MissionGap>> {
        local.firstNotNullOfOrNull { it.autonomy(id) }?.let { api -> return polled { api.navStatus(null) } }
        return nodes.node(id)
            .map { it?.reaches?.firstOrNull() }
            .distinctUntilChanged()
            .flatMapLatest<Reach?, List<MissionGap>> { reach ->
                val client = when (reach) {
                    Reach.Lan -> clients.forNode(id)
                    is Reach.ViaGround -> clients.forCarried(reach.groundId, id)
                    else -> null
                } ?: return@flatMapLatest flowOf(emptyList())
                polled { client.nav.status() }
            }
            .distinctUntilChanged()
    }

    private fun polled(read: suspend () -> AgentResult<JsonObject>?): Flow<List<MissionGap>> = flow {
        while (true) {
            emit(gapsOf(read()?.getOrNull()))
            delay(POLL_MS)
        }
    }.distinctUntilChanged()

    companion object {
        const val POLL_MS = 2_000L

        /** The skipped waypoints in a navigation status body's `pilot` frame (`{pilot: {ts_ms, age_ms, data}}`). */
        fun gapsOf(status: JsonObject?): List<MissionGap> {
            val data = ((status?.get("pilot") as? JsonObject)?.get("data") as? JsonObject) ?: return emptyList()
            return PilotWire.parseState(data, 0L)?.mission?.gaps.orEmpty()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MissionGapsModule {
    @Binds
    abstract fun bindMissionGapsSource(impl: AgentMissionGapsSource): MissionGapsSource
}
