package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.fcsetup.AgentSetupBackend
import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.agent.ws.AgentSockets
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Resolves the setup engine of a node. Emits null while the vehicle is reached
 * straight over MAVLink (a flight controller on the phone), where the configure
 * screens drive the flight controller themselves.
 */
fun interface SetupBackendSource {
    fun observe(id: DeviceId): Flow<SetupBackend?>
}

/** The node's best reach goes through an agent (its own LAN edge or the carrying ground station). */
fun NodeSummary.agentConnected(): Boolean = when (reaches.firstOrNull()) {
    Reach.Lan, is Reach.ViaGround -> true
    else -> false
}

/** The agent's setup engine for agent-connected nodes, or one served in-process ([LocalNodeApis.fcSetup]). */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AgentSetupBackendSource @Inject constructor(
    private val nodes: NodeRepository,
    private val clients: AgentClientFactory,
    private val sockets: AgentSockets,
    private val locals: Set<@JvmSuppressWildcards LocalNodeApis>,
) : SetupBackendSource {
    private fun local(id: DeviceId): SetupBackend? = locals.firstNotNullOfOrNull { it.fcSetup(id) }

    override fun observe(id: DeviceId): Flow<SetupBackend?> {
        local(id)?.let { return flowOf(it) }
        return nodes.node(id)
            .map { node -> node?.takeIf { it.agentConnected() }?.reaches?.firstOrNull() }
            .distinctUntilChanged()
            .mapLatest { reach ->
                val client = when (reach) {
                    Reach.Lan -> clients.forNode(id)
                    is Reach.ViaGround -> clients.forCarried(reach.groundId, id)
                    else -> null
                }
                client?.let { AgentSetupBackend(it, sockets) }
            }
    }

    /** The setup engine of [id] right now, or null when the vehicle has none. */
    suspend fun current(id: DeviceId): SetupBackend? {
        local(id)?.let { return it }
        val node = nodes.nodes.filterNotNull().first().firstOrNull { it.id == id } ?: return null
        if (!node.agentConnected()) return null
        return when (val reach = node.reaches.first()) {
            Reach.Lan -> clients.forNode(id)
            is Reach.ViaGround -> clients.forCarried(reach.groundId, id)
            else -> null
        }?.let { AgentSetupBackend(it, sockets) }
    }
}
