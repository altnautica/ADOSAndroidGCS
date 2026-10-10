package com.altnautica.gcs.core.agent.pairing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The node-identity probe returned by `GET /api/pairing/info`.
 *
 * The agent emits every field even when null, so a null here means the agent
 * genuinely does not know it rather than that the field was omitted. Only the
 * fields this client acts on are modelled; unknown keys are ignored.
 */
@Serializable
data class PairingInfo(
    @SerialName("device_id") val deviceId: String? = null,
    val name: String? = null,
    val version: String? = null,
    val board: String? = null,
    val paired: Boolean = false,
    @SerialName("radio_paired") val radioPaired: Boolean? = null,
    @SerialName("pairing_code") val pairingCode: String? = null,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("mdns_host") val mdnsHost: String? = null,
    val profile: String? = null,
    val role: String? = null,
)

/** Body of `POST /api/pairing/claim`. */
@Serializable
data class ClaimRequest(
    @SerialName("user_id") val userId: String,
)

/** Success body of `POST /api/pairing/claim`. */
@Serializable
data class ClaimResponse(
    @SerialName("api_key") val apiKey: String,
    @SerialName("device_id") val deviceId: String? = null,
    val name: String? = null,
    @SerialName("mdns_host") val mdnsHost: String? = null,
)

/** Success body of `POST /api/pairing/unpair`. */
@Serializable
data class UnpairResponse(
    val status: String? = null,
    @SerialName("new_code") val newCode: String? = null,
)

/**
 * Raised when a request reached a paired agent without a usable key.
 *
 * Distinct from a transport failure so the UI can say "pair this device" rather
 * than "the ground station is unreachable" — the node answered, it refused.
 */
class NotPairedError(message: String) : Exception(message)

/** Raised when the agent is already claimed by another operator (HTTP 409). */
class AlreadyPairedError(message: String) : Exception(message)
