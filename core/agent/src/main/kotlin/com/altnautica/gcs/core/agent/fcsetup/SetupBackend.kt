package com.altnautica.gcs.core.agent.fcsetup

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.ws.AgentSockets
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * The setup engine for one vehicle: the wizards, their live step updates and step actions.
 * An agent serves it over the `/api/v1/fc-setup` routes ([AgentSetupBackend]); a simulated
 * vehicle can serve it in-process.
 */
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
