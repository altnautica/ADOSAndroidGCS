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
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import com.altnautica.gcs.core.model.DeviceId
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * The phone's credential store exists but could not be read just now, so
     * nothing could be saved or removed. Retrying the same step is safe: a node
     * that was claimed but not saved is saved by the retry without claiming again.
     */
    data object VaultUnavailable : PairingFailure
}

sealed interface PairingOutcome<out T> {
    data class Success<T>(val value: T) : PairingOutcome<T>
    data class Failure(val failure: PairingFailure) : PairingOutcome<Nothing>
}

/**
 * LAN pairing against any number of nodes: probe, claim, add an already-paired
 * node by key, unpair, forget. Every credential lands in [CredentialVault].
 *
 * Claiming and unpairing change the agent and cannot be repeated, so the vault
 * is checked writable first; if it still fails after the agent changed, the
 * outcome is kept in memory and the next attempt of the same step only retries
 * the local save or removal.
 */
@Singleton
class PairingService @Inject constructor(
    private val clients: AgentClientFactory,
    private val discovery: LanDiscovery,
    private val vault: CredentialVault,
) {
    /** Credentials an agent issued that the vault refused, by the host they were claimed on. */
    private val unsavedClaims = ConcurrentHashMap<String, NodeCredential>()

    /** Nodes released on the agent whose local key the vault refused to drop. */
    private val unremoved: MutableSet<DeviceId> = ConcurrentHashMap.newKeySet()

    /**
     * True when a node at [host] was claimed but its key could not be saved. The
     * node now reports itself paired, so the retry must call [claim] (which only
     * saves) rather than probe and ask for a key.
     */
    fun hasUnsavedClaim(host: String): Boolean = unsavedClaims.containsKey(host)

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
        unsavedClaims[host]?.let { pending ->
            // Claimed on an earlier attempt but not saved: only the save is retried.
            val saved = save(pending)
            if (saved is PairingOutcome.Success) unsavedClaims.remove(host)
            return saved
        }
        val client = clients.forHost(host) ?: return fail(PairingFailure.InvalidHost)
        val info = client.pairingInfo()
        if (info is AgentResult.Ok && info.value.paired) return fail(PairingFailure.AlreadyPaired)
        val userId = try {
            // The claim cannot be repeated (a claimed agent answers 409), so a vault that
            // would refuse the key stops it here, before the agent hands one out.
            vault.requireWritable()
            cloudUserId ?: vault.installId()
        } catch (_: VaultUnreadableException) {
            return fail(PairingFailure.VaultUnavailable)
        }
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
                val credential = credential(client, deviceId, body.apiKey, body.name ?: info.getOrNull()?.name, body.mdnsHost, info.getOrNull()?.profile)
                save(credential).also { if (it is PairingOutcome.Failure) unsavedClaims[host] = credential }
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
            is AgentResult.Ok -> save(credential(authed, deviceId, apiKey.trim(), info.name, info.mdnsHost, info.profile))
        }
    }

    /** Release the node on the agent, then drop the local key. */
    suspend fun unpair(node: DeviceId): PairingOutcome<Unit> {
        // Released on the agent already; only the local removal is left.
        if (node in unremoved) return forget(node)
        try {
            vault.requireWritable()
        } catch (_: VaultUnreadableException) {
            return fail(PairingFailure.VaultUnavailable)
        }
        val client = clients.forNode(node) ?: return fail(PairingFailure.Agent(AgentError.Unauthorized))
        return when (val r = client.unpair().flatMap { AgentResult.Ok(Unit) }) {
            is AgentResult.Ok -> forget(node).also { if (it is PairingOutcome.Failure) unremoved += node }
            is AgentResult.Err -> fail(PairingFailure.Agent(r.error))
        }
    }

    /** Forget the node locally without contacting it (it was unpaired elsewhere). */
    suspend fun forget(node: DeviceId): PairingOutcome<Unit> = try {
        vault.removeNode(node)
        unremoved -= node
        PairingOutcome.Success(Unit)
    } catch (_: VaultUnreadableException) {
        fail(PairingFailure.VaultUnavailable)
    }

    private fun credential(
        client: AgentClient,
        deviceId: String,
        apiKey: String,
        name: String?,
        mdnsHost: String?,
        profile: String?,
    ): NodeCredential {
        val base = client.endpoint.baseUrl
        return NodeCredential(
            deviceId = deviceId,
            apiKey = apiKey,
            name = name,
            mdnsHost = mdnsHost,
            lastIp = base.host,
            lastPort = base.port,
            profile = profile,
        )
    }

    private suspend fun save(credential: NodeCredential): PairingOutcome<NodeCredential> = try {
        vault.putNode(credential)
        PairingOutcome.Success(credential)
    } catch (_: VaultUnreadableException) {
        fail(PairingFailure.VaultUnavailable)
    }

    private fun <T> AgentResult<T>.toOutcome(): PairingOutcome<T> = when (this) {
        is AgentResult.Ok -> PairingOutcome.Success(value)
        is AgentResult.Err -> fail(PairingFailure.Agent(error))
    }

    private fun fail(f: PairingFailure) = PairingOutcome.Failure(f)
}
