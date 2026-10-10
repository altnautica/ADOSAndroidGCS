package com.altnautica.gcs.radio.rx.invite

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** A request the ground station created for this phone. */
data class CreatedInvite(val inviteId: String, val phoneFingerprint: String, val expiresAtMs: Long)

/** A phone waiting for the operator on the ground station. */
@Serializable
data class PendingInvite(
    @SerialName("invite_id") val inviteId: String,
    val label: String? = null,
    @SerialName("phone_fingerprint") val phoneFingerprint: String,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
)

sealed interface InvitePoll {
    data object Pending : InvitePoll
    class Approved(val sealed: SealedInvite) : InvitePoll
    data object Rejected : InvitePoll

    /** Timed out, already fetched, or forgotten by the ground station. */
    data object Expired : InvitePoll
}

sealed interface InviteCallResult<out T> {
    data class Ok<T>(val value: T) : InviteCallResult<T>

    /** The node does not offer phone invites (not a ground station, or an older agent). */
    data object Unsupported : InviteCallResult<Nothing>
    data class Failed(val reason: InviteFailure) : InviteCallResult<Nothing>
}

enum class InviteFailure { NotPaired, Unauthorized, GroundNotLinked, TooManyPending, InvalidKey, Network, Server }

/** The ground-station invite routes. */
interface PhoneInviteApi {
    suspend fun create(ground: DeviceId, phonePublic: ByteArray, label: String): InviteCallResult<CreatedInvite>
    suspend fun poll(ground: DeviceId, inviteId: String): InviteCallResult<InvitePoll>
    suspend fun pending(ground: DeviceId): InviteCallResult<List<PendingInvite>>
    suspend fun approve(ground: DeviceId, inviteId: String): InviteCallResult<Unit>
    suspend fun reject(ground: DeviceId, inviteId: String): InviteCallResult<Unit>
}

@Singleton
class AgentPhoneInviteApi @Inject constructor(private val clients: AgentClientFactory) : PhoneInviteApi {

    override suspend fun create(ground: DeviceId, phonePublic: ByteArray, label: String): InviteCallResult<CreatedInvite> {
        val body = buildJsonObject {
            put("phone_pub_b64", JsonPrimitive(Base64.getEncoder().encodeToString(phonePublic)))
            put("label", JsonPrimitive(label))
        }
        return call(ground, "POST", BASE, body) { json ->
            val o = json as? JsonObject ?: return@call null
            CreatedInvite(
                inviteId = o.string("invite_id") ?: return@call null,
                phoneFingerprint = o.string("phone_fingerprint") ?: return@call null,
                expiresAtMs = o["expires_at_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@call null,
            )
        }
    }

    override suspend fun poll(ground: DeviceId, inviteId: String): InviteCallResult<InvitePoll> {
        val client = clients.forNode(ground) ?: return InviteCallResult.Failed(InviteFailure.NotPaired)
        return when (val r = client.requestJson("GET", "$BASE/${segment(inviteId)}")) {
            is AgentResult.Ok -> parsePoll(r.value)?.let { InviteCallResult.Ok(it) } ?: InviteCallResult.Failed(InviteFailure.Server)
            is AgentResult.Err -> when (val e = r.error) {
                // 403 {state:"rejected"}: the request itself was authenticated
                // a moment ago, so a refusal here is the operator's answer.
                AgentError.Unauthorized -> InviteCallResult.Ok(InvitePoll.Rejected)
                is AgentError.Server -> if (e.code == 410 || e.code == 404) InviteCallResult.Ok(InvitePoll.Expired) else failure(e)
                AgentError.FeatureAbsent -> InviteCallResult.Ok(InvitePoll.Expired)
                else -> failure(e)
            }
        }
    }

    override suspend fun pending(ground: DeviceId): InviteCallResult<List<PendingInvite>> =
        call(ground, "GET", BASE, null) { json ->
            val list = (json as? JsonObject)?.get("pending") ?: return@call emptyList()
            runCatching { lenient.decodeFromJsonElement(PendingList.serializer(), JsonObject(mapOf("pending" to list))).pending }.getOrNull()
        }

    override suspend fun approve(ground: DeviceId, inviteId: String): InviteCallResult<Unit> =
        call(ground, "POST", "$BASE/${segment(inviteId)}/approve", null) { }

    override suspend fun reject(ground: DeviceId, inviteId: String): InviteCallResult<Unit> =
        call(ground, "POST", "$BASE/${segment(inviteId)}/reject", null) { }

    private suspend fun <T> call(
        ground: DeviceId,
        method: String,
        path: String,
        body: JsonElement?,
        parse: (JsonElement) -> T?,
    ): InviteCallResult<T> {
        val client = clients.forNode(ground) ?: return InviteCallResult.Failed(InviteFailure.NotPaired)
        return when (val r = client.requestJson(method, path, body)) {
            is AgentResult.Ok -> parse(r.value)?.let { InviteCallResult.Ok(it) } ?: InviteCallResult.Failed(InviteFailure.Server)
            is AgentResult.Err -> failure(r.error)
        }
    }

    private fun <T> failure(error: AgentError): InviteCallResult<T> = when (error) {
        AgentError.FeatureAbsent, AgentError.ProfileMismatch -> InviteCallResult.Unsupported
        AgentError.Unauthorized, is AgentError.PinRequired -> InviteCallResult.Failed(InviteFailure.Unauthorized)
        is AgentError.Network -> InviteCallResult.Failed(InviteFailure.Network)
        is AgentError.Server -> InviteCallResult.Failed(
            when {
                error.message == "E_GS_NOT_PAIRED" || error.code == 409 -> InviteFailure.GroundNotLinked
                error.message == "E_INVITE_LIMIT" || error.code == 429 -> InviteFailure.TooManyPending
                error.message == "E_PHONE_PUB_INVALID" || error.code == 400 -> InviteFailure.InvalidKey
                else -> InviteFailure.Server
            },
        )
        else -> InviteCallResult.Failed(InviteFailure.Server)
    }

    @Serializable
    private data class PendingList(val pending: List<PendingInvite> = emptyList())

    companion object {
        const val BASE = "api/v1/ground-station/wfb/invite"

        private val lenient = Json { ignoreUnknownKeys = true }

        /** Decodes a poll body: `{state:"pending"}` or the sealed bundle. */
        fun parsePoll(json: JsonElement): InvitePoll? {
            val o = json as? JsonObject ?: return null
            val pub = o.string("gs_eph_pub_b64")
            val nonce = o.string("nonce_b64")
            val ct = o.string("ciphertext_b64")
            if (pub != null && nonce != null && ct != null) {
                val d = Base64.getDecoder()
                return runCatching { InvitePoll.Approved(SealedInvite(d.decode(pub), d.decode(nonce), d.decode(ct))) }.getOrNull()
            }
            return when (o.string("state")) {
                "pending" -> InvitePoll.Pending
                "rejected" -> InvitePoll.Rejected
                "expired" -> InvitePoll.Expired
                else -> null
            }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
