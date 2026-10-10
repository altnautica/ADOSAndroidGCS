package com.altnautica.gcs.core.cloud.node

import com.altnautica.gcs.core.cloud.convex.CloudJson
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CloudClaimResult {
    /**
     * The node is now on the account. [lanKeySaved] is false when this phone
     * could not store the node's agent key; the key is held in memory until
     * [CloudPairing.retrySavePending] stores it.
     */
    data class Claimed(val deviceId: DeviceId, val name: String?, val lanKeySaved: Boolean = true) : CloudClaimResult
    data class Refused(val error: ClaimError, val retryAfterMs: Long? = null) : CloudClaimResult
    data class Failed(val message: String) : CloudClaimResult
}

/** [VaultUnavailable] is this phone's refusal: the code was not sent and can be claimed again. */
enum class ClaimError { InvalidCode, Expired, AlreadyClaimed, OwnedByOther, RateLimited, Unknown, VaultUnavailable }

/**
 * Claims a node by the pairing code it shows. The returned agent key is also
 * sealed in the credential vault, so the node is reachable on the LAN as well.
 *
 * A claim cannot be repeated (the code is spent), so it is not sent while the
 * vault refuses writes. If the vault refuses the key after the claim went
 * through, the key is kept in memory for [retrySavePending].
 */
@Singleton
class CloudPairing @Inject constructor(
    private val gateway: ConvexGateway,
    private val vault: CredentialVault,
) {
    private val lock = Mutex()
    private val unsaved = mutableMapOf<String, NodeCredential>()

    suspend fun claim(code: String): CloudClaimResult = try {
        try {
            vault.requireWritable()
        } catch (e: VaultUnreadableException) {
            return CloudClaimResult.Refused(ClaimError.VaultUnavailable)
        }
        val r = parse(gateway.mutation("cmdPairing:claimPairingCode", mapOf("code" to code.trim().uppercase())))
        if (r.error != null) {
            CloudClaimResult.Refused(errorOf(r.error), r.retryAfterMs?.toLong())
        } else {
            val id = r.deviceId ?: return CloudClaimResult.Failed("Claim returned no device")
            val saved = r.apiKey.isNullOrBlank() ||
                save(NodeCredential(deviceId = id, apiKey = r.apiKey, name = r.name, mdnsHost = r.mdnsHost, lastIp = r.localIp))
            CloudClaimResult.Claimed(DeviceId(id), r.name, lanKeySaved = saved)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CloudClaimResult.Failed(e.message ?: "Claim failed")
    }

    /** Stores every claimed key the vault refused earlier; true when none is left unsaved. */
    suspend fun retrySavePending(): Boolean = lock.withLock {
        unsaved.values.toList().forEach { storeLocked(it) }
        unsaved.isEmpty()
    }

    private suspend fun save(credential: NodeCredential): Boolean = lock.withLock { storeLocked(credential) }

    /** Stores [credential]; when the vault refuses it, keeps it in memory instead of losing it. */
    private suspend fun storeLocked(credential: NodeCredential): Boolean = try {
        vault.putNode(credential)
        unsaved.remove(credential.deviceId)
        true
    } catch (e: CancellationException) {
        unsaved[credential.deviceId] = credential
        throw e
    } catch (e: Exception) {
        unsaved[credential.deviceId] = credential
        false
    }

    @Serializable
    internal data class ClaimResponse(
        val error: String? = null,
        val deviceId: String? = null,
        val apiKey: String? = null,
        val name: String? = null,
        val mdnsHost: String? = null,
        val localIp: String? = null,
        val retryAfterMs: Double? = null,
    )

    companion object {
        internal fun parse(json: JsonElement): ClaimResponse = CloudJson.decodeFromJsonElement(ClaimResponse.serializer(), json)

        fun errorOf(code: String): ClaimError = when (code) {
            "invalid_pairing_code" -> ClaimError.InvalidCode
            "pairing_code_expired" -> ClaimError.Expired
            "code_already_claimed" -> ClaimError.AlreadyClaimed
            "device_owned_by_other" -> ClaimError.OwnedByOther
            "rate_limited" -> ClaimError.RateLimited
            else -> ClaimError.Unknown
        }
    }
}
