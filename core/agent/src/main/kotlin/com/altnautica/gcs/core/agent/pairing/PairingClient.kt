package com.altnautica.gcs.core.agent.pairing

import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.http.AgentResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the agent's LAN pairing contract.
 *
 * `info`, `code` and `claim` are in the agent's public (no-key) set, so they
 * answer on a paired node too; `unpair` sits behind the normal key gate and
 * therefore needs the stored key on the request. [claim] and [unpair] return
 * the raw [AgentResponse] because their non-2xx codes (409, 401) carry meaning
 * the repository maps to specific errors.
 */
@Singleton
class PairingClient @Inject constructor(
    private val http: AgentHttp,
) {

    suspend fun getInfo(): PairingInfo =
        http.decode(http.send("GET", "api/pairing/info"), PairingInfo.serializer())

    suspend fun claim(body: ClaimRequest): AgentResponse =
        http.send("POST", "api/pairing/claim", ClaimRequest.serializer(), body)

    suspend fun unpair(): AgentResponse = http.send("POST", "api/pairing/unpair")

    /** Decode a successful claim body. */
    fun decodeClaim(response: AgentResponse): ClaimResponse =
        http.decode(response, ClaimResponse.serializer())
}
