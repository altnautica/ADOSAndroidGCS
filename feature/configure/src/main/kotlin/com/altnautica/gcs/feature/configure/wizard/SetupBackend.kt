package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.fcsetup.FcSetupApi
import com.altnautica.gcs.core.agent.fcsetup.FcSetupEvent
import com.altnautica.gcs.core.agent.fcsetup.FcSetupOverview
import com.altnautica.gcs.core.agent.fcsetup.RevertReply
import com.altnautica.gcs.core.agent.fcsetup.StepReply
import com.altnautica.gcs.core.agent.fcsetup.WizardReply
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.ws.AgentSockets
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** The agent's setup engine for one vehicle: the wizards, their live step updates and step actions. */
interface SetupBackend {
    suspend fun overview(): AgentResult<FcSetupOverview>
    fun stream(): Flow<FcSetupEvent>
    suspend fun start(wizard: String): AgentResult<WizardReply>
    suspend fun action(wizard: String, step: String, action: String, args: JsonObject? = null): AgentResult<StepReply>
    suspend fun runStep(wizard: String, step: String): AgentResult<StepReply>
    suspend fun abort(wizard: String): AgentResult<WizardReply>
    suspend fun revert(snapshot: String): AgentResult<RevertReply>
}

class AgentSetupBackend(private val client: AgentClient, private val sockets: AgentSockets) : SetupBackend {
    private val api get() = client.fcSetup
    override suspend fun overview() = api.overview()
    override fun stream() = api.stream(sockets)
    override suspend fun start(wizard: String) = api.start(wizard)
    override suspend fun action(wizard: String, step: String, action: String, args: JsonObject?) =
        api.action(wizard, step, action, args)
    override suspend fun runStep(wizard: String, step: String) = api.runStep(wizard, step)
    override suspend fun abort(wizard: String) = api.abort(wizard)
    override suspend fun revert(snapshot: String) = api.revert(snapshot)
}

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

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AgentSetupBackendSource @Inject constructor(
    private val nodes: NodeRepository,
    private val clients: AgentClientFactory,
    private val sockets: AgentSockets,
) : SetupBackendSource {
    override fun observe(id: DeviceId): Flow<SetupBackend?> = nodes.node(id)
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

    /** The setup engine of [id] right now, or null when the vehicle is not agent-connected. */
    suspend fun current(id: DeviceId): SetupBackend? {
        val node = nodes.nodes.filterNotNull().first().firstOrNull { it.id == id } ?: return null
        if (!node.agentConnected()) return null
        return when (val reach = node.reaches.first()) {
            Reach.Lan -> clients.forNode(id)
            is Reach.ViaGround -> clients.forCarried(reach.groundId, id)
            else -> null
        }?.let { AgentSetupBackend(it, sockets) }
    }
}
