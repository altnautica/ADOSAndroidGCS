package com.altnautica.gcs.core.link.fleet

/** The aux uplink's Request lane to one drone, as the fleet's ground uses it. */
interface FleetRequestLane {
    /**
     * Sends one request to [deviceId] and returns the drone's HTTP status, or
     * null when no answer came (the phone does not hold the uplink, or the
     * drone did not answer in time). With [relaySecretHex] the request carries
     * a relay ticket minted from that secret and bound to [method], [path] and
     * [body]; without, it carries none (only the secret offer itself does that).
     */
    suspend fun request(
        deviceId: String,
        method: String,
        path: String,
        contentType: String?,
        body: ByteArray,
        relaySecretHex: String?,
    ): Int?
}
