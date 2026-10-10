package com.altnautica.gcs.core.agent.pairing

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.discovery.DiscoveredAgent
import com.altnautica.gcs.core.agent.discovery.LanDiscovery
import com.altnautica.gcs.core.agent.discovery.PairingCode
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.http.flatMap
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.model.DeviceId
import javax.inject.Inject
import javax.inject.Singleton

/** Why a pairing step could not complete, beyond a transport or agent error. */
sealed interface PairingFailure {
    /** The node is already claimed by another operator; it must be unpaired there first. */
    data object AlreadyPaired : PairingFailure

    /** No discovered node advertises that code; fall back to a manual host. */
    data object CodeNotFound : PairingFailure

    /** The input is not a valid pairing code. */
    data object InvalidCode : PairingFailure

    /** The host string cannot be turned into an address. */
    data object InvalidHost : PairingFailure

    /** The agent answered without a device id or key. */
    data object IncompleteAnswer : PairingFailure

    data class Agent(val error: AgentError) : PairingFailure
}

sealed interface PairingOutcome<out T> {
    data class Success<T>(val value: T) : PairingOutcome<T>
    data class Failure(val failure: PairingFailure) : PairingOutcome<Nothing>
}

/**
 * LAN pairing against any number of nodes: probe, claim, add an already-paired
 * node by key, unpair, forget. Every credential lands in [CredentialVault].
 */
@Singleton
class PairingService @Inject constructor(
    private val clients: AgentClientFactory,
    private val discovery: LanDiscovery,
    private val vault: CredentialVault,
) {

    /** `GET /api/pairing/info` on a typed host. */
    suspend fun probe(host: String): PairingOutcome<PairingInfo> {
        val client = clients.forHost(host) ?: return fail(PairingFailure.InvalidHost)
        return client.pairingInfo().toOutcome()
    }

    /** The discovered node advertising [code] (TXT `code`), for the code flow. */
    fun resolveCode(code: String): PairingOutcome<DiscoveredAgent> {
        val normalized = PairingCode.normalize(code)
        if (!PairingCode.isValid(normalized)) return fail(PairingFailure.InvalidCode)
        return discovery.findByCode(normalized)?.let { PairingOutcome.Success(it) } ?: fail(PairingFailure.CodeNotFound)
    }

    /**
     * Claim the node at [host]. [cloudUserId] is the signed-in cloud user; when
     * absent the per-install id is presented so a later re-pair is recognised.
     */
    suspend fun claim(host: String, cloudUserId: String? = null): PairingOutcome<NodeCredential> {
        val client = clients.forHost(host) ?: return fail(PairingFailure.InvalidHost)
        val info = client.pairingInfo()
        if (info is AgentResult.Ok && info.value.paired) return fail(PairingFailure.AlreadyPaired)
        val userId = cloudUserId ?: vault.installId()
        return when (val claimed = client.claim(userId)) {
            is AgentResult.Err ->
                if (claimed.error is AgentError.Server && (claimed.error as AgentError.Server).code == 409) {
                    fail(PairingFailure.AlreadyPaired)
                } else {
                    fail(PairingFailure.Agent(claimed.error))
                }
            is AgentResult.Ok -> {
                val body = claimed.value
                val deviceId = body.deviceId ?: (info as? AgentResult.Ok)?.value?.deviceId
                if (body.apiKey.isBlank() || deviceId.isNullOrBlank()) return fail(PairingFailure.IncompleteAnswer)
                store(client, deviceId, body.apiKey, body.name ?: info.getOrNull()?.name, body.mdnsHost, info.getOrNull()?.profile)
            }
        }
    }

    /**
     * Add a node this install did not claim but whose key the operator holds.
     * The key is proven with an authenticated read before it is stored.
     */
    suspend fun addWithKey(host: String, apiKey: String): PairingOutcome<NodeCredential> {
        val probe = clients.forHost(host) ?: return fail(PairingFailure.InvalidHost)
        val info = when (val r = probe.pairingInfo()) {
            is AgentResult.Ok -> r.value
            is AgentResult.Err -> return fail(PairingFailure.Agent(r.error))
        }
        val deviceId = info.deviceId ?: return fail(PairingFailure.IncompleteAnswer)
        val authed = clients.forHost(host, apiKey.trim(), DeviceId(deviceId))!!
        return when (val check = authed.status()) {
            is AgentResult.Err -> fail(PairingFailure.Agent(check.error))
            is AgentResult.Ok -> store(authed, deviceId, apiKey.trim(), info.name, info.mdnsHost, info.profile)
        }
    }

    /** Release the node on the agent, then drop the local key. */
    suspend fun unpair(node: DeviceId): PairingOutcome<Unit> {
        val client = clients.forNode(node) ?: return fail(PairingFailure.Agent(AgentError.Unauthorized))
        return client.unpair().flatMap { AgentResult.Ok(Unit) }.let { r ->
            when (r) {
                is AgentResult.Ok -> {
                    vault.removeNode(node)
                    PairingOutcome.Success(Unit)
                }
                is AgentResult.Err -> fail(PairingFailure.Agent(r.error))
            }
        }
    }

    /** Forget the node locally without contacting it (it was unpaired elsewhere). */
    suspend fun forget(node: DeviceId) = vault.removeNode(node)

    private suspend fun store(
        client: AgentClient,
        deviceId: String,
        apiKey: String,
        name: String?,
        mdnsHost: String?,
        profile: String?,
    ): PairingOutcome<NodeCredential> {
        val base = client.endpoint.baseUrl
        val credential = NodeCredential(
            deviceId = deviceId,
            apiKey = apiKey,
            name = name,
            mdnsHost = mdnsHost,
            lastIp = base.host,
            lastPort = base.port,
            profile = profile,
        )
        vault.putNode(credential)
        return PairingOutcome.Success(credential)
    }

    private fun <T> AgentResult<T>.toOutcome(): PairingOutcome<T> = when (this) {
        is AgentResult.Ok -> PairingOutcome.Success(value)
        is AgentResult.Err -> fail(PairingFailure.Agent(error))
    }

    private fun fail(f: PairingFailure) = PairingOutcome.Failure(f)
}
