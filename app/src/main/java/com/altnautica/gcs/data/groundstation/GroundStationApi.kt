package com.altnautica.gcs.data.groundstation

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT

/**
 * Retrofit binding for the ground-station REST surface served by the
 * ADOS Drone Agent under /api/v1/ground-station. The base URL is set in
 * DataModule; each path here is relative to that root.
 *
 * Recording, camera switch, video stats, system info/reboot, and OTA push
 * have no agent counterpart on this profile yet. Repository surfaces a
 * "not implemented" Result.failure for callers until those endpoints land.
 */
interface GroundStationApi {

    @GET("api/v1/ground-station/status")
    suspend fun getStatus(): StationStatus

    @GET("api/v1/ground-station/wfb")
    suspend fun getWfb(): WfbConfig

    @PUT("api/v1/ground-station/wfb")
    suspend fun putWfb(@Body update: WfbUpdate): WfbConfig

    @GET("api/v1/ground-station/network")
    suspend fun getNetwork(): NetworkConfig

    @PUT("api/v1/ground-station/network/ap")
    suspend fun putNetworkAp(@Body update: ApUpdate): Response<ApConfig>
}
