package com.altnautica.gcs.data.pairing

import com.google.gson.annotations.SerializedName

/**
 * The node-identity probe returned by `GET /api/pairing/info`.
 *
 * The agent emits every field even when null, so a missing value here means the
 * agent genuinely does not know it rather than that the field was omitted. Only
 * the fields this client acts on are modelled; Gson ignores the rest.
 */
data class PairingInfo(
    @SerializedName("device_id") val deviceId: String = "",
    val name: String = "",
    val version: String = "",
    val board: String = "",
    val paired: Boolean = false,
    @SerializedName("radio_paired") val radioPaired: Boolean = false,
    @SerializedName("pairing_code") val pairingCode: String? = null,
    @SerializedName("owner_id") val ownerId: String? = null,
    @SerializedName("mdns_host") val mdnsHost: String? = null,
    val profile: String = "",
    val role: String? = null,
)

/** Body of `POST /api/pairing/claim`. */
data class ClaimRequest(
    @SerializedName("user_id") val userId: String,
)

/** Success body of `POST /api/pairing/claim`. */
data class ClaimResponse(
    @SerializedName("api_key") val apiKey: String = "",
    @SerializedName("device_id") val deviceId: String = "",
    val name: String = "",
    @SerializedName("mdns_host") val mdnsHost: String = "",
)

/** Success body of `POST /api/pairing/unpair`. */
data class UnpairResponse(
    val status: String = "",
    @SerializedName("new_code") val newCode: String? = null,
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
