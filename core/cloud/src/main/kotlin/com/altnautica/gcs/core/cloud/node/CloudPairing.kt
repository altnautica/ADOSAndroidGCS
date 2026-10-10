package com.altnautica.gcs.core.cloud.node

import com.altnautica.gcs.core.cloud.convex.CloudJson
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CloudClaimResult {
    data class Claimed(val deviceId: DeviceId, val name: String?) : CloudClaimResult
    data class Refused(val error: ClaimError, val retryAfterMs: Long? = null) : CloudClaimResult
    data class Failed(val message: String) : CloudClaimResult
}

enum class ClaimError { InvalidCode, Expired, AlreadyClaimed, OwnedByOther, RateLimited, Unknown }

/**
 * Claims a node by the pairing code it shows. The returned agent key is also
 * sealed in the credential vault, so the node is reachable on the LAN as well.
 */
@Singleton
class CloudPairing @Inject constructor(
    private val gateway: ConvexGateway,
    private val vault: CredentialVault,
) {
    suspend fun claim(code: String): CloudClaimResult = try {
        val r = parse(gateway.mutation("cmdPairing:claimPairingCode", mapOf("code" to code.trim().uppercase())))
        if (r.error != null) {
            CloudClaimResult.Refused(errorOf(r.error), r.retryAfterMs?.toLong())
        } else {
            val id = r.deviceId ?: return CloudClaimResult.Failed("Claim returned no device")
            if (!r.apiKey.isNullOrBlank()) {
                vault.putNode(NodeCredential(deviceId = id, apiKey = r.apiKey, name = r.name, mdnsHost = r.mdnsHost, lastIp = r.localIp))
            }
            CloudClaimResult.Claimed(DeviceId(id), r.name)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CloudClaimResult.Failed(e.message ?: "Claim failed")
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
