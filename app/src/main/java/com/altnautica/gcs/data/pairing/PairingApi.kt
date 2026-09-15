package com.altnautica.gcs.data.pairing

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit binding for the agent's LAN pairing contract.
 *
 * `info`, `code` and `claim` are in the agent's public (no-key) set, so they
 * answer on a paired node too; `unpair` sits behind the normal key gate and
 * therefore needs the stored key on the request.
 */
interface PairingApi {

    @GET("api/pairing/info")
    suspend fun getInfo(): PairingInfo

    @POST("api/pairing/claim")
    suspend fun claim(@Body body: ClaimRequest): Response<ClaimResponse>

    @POST("api/pairing/unpair")
    suspend fun unpair(): Response<UnpairResponse>
}
