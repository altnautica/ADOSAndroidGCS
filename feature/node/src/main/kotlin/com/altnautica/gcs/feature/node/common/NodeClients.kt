package com.altnautica.gcs.feature.node.common

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import javax.inject.Inject
import javax.inject.Singleton

/** Picks the agent client for a node: its own LAN key, else the ground station that carries it. */
@Singleton
class NodeClients @Inject constructor(private val factory: AgentClientFactory) {

    suspend fun clientFor(node: NodeSummary): AgentClient? {
        factory.forNode(node.id)?.let { return it }
        val ground = node.reaches.firstNotNullOfOrNull { it as? Reach.ViaGround } ?: return null
        return factory.forCarried(ground.groundId, node.id)
    }
}

/** What a node page shows when it cannot read the agent (CS-9). */
sealed interface PageProblem {
    /** The node has no agent link this app can use (not paired, cloud only, offline). */
    data object NoAgentLink : PageProblem
    data object Unauthorized : PageProblem
    data class PinRequired(val message: String?) : PageProblem
    /** The agent does not serve this page (older agent or other profile). */
    data object Unsupported : PageProblem
    data class Unreachable(val message: String?) : PageProblem

    companion object {
        fun from(error: AgentError): PageProblem = when (error) {
            AgentError.Unauthorized -> Unauthorized
            is AgentError.PinRequired -> PinRequired(error.message)
            AgentError.FeatureAbsent, AgentError.ProfileMismatch -> Unsupported
            is AgentError.Network -> Unreachable(error.cause.message)
            is AgentError.Server -> Unreachable(error.message ?: "HTTP ${error.code}")
            is AgentError.PeerApi -> Unreachable(error.message ?: error.code)
            is AgentError.RelayTicket -> Unreachable(error.reason)
            is AgentError.Armed -> Unreachable(null)
        }
    }
}
