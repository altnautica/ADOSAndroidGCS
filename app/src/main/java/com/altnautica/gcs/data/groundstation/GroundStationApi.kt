package com.altnautica.gcs.data.groundstation

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

/**
 * Retrofit binding for the ground-station REST surface served by the
 * ADOS Drone Agent under /api/v1/ground-station, plus the agent-wide
 * supervisor restart. The request host is supplied per call by
 * AgentHostInterceptor; each path here is the full agent path.
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

    @POST("api/v1/ground-station/recording/start")
    suspend fun startRecording(@Body body: RecordingStartRequest): RecordingStartResponse

    @POST("api/v1/ground-station/recording/stop")
    suspend fun stopRecording(): RecordingStopResponse

    @GET("api/v1/ground-station/recording/list")
    suspend fun listRecordings(): RecordingListResponse

    /**
     * Cycle the agent's own service tree (`ados-*` units). Not a machine
     * reboot — the agent serves no OS-reboot route, so this is the strongest
     * recovery action reachable over HTTP.
     */
    @POST("api/v1/system/restart-supervisor")
    suspend fun restartSupervisor(): Response<Unit>

    @POST("api/v1/ground-station/camera/switch")
    suspend fun switchCamera(@Body body: CameraSwitchRequest): Response<CameraSwitchResponse>
}
