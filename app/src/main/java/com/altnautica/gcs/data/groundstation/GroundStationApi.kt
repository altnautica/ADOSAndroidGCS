package com.altnautica.gcs.data.groundstation

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT

interface GroundStationApi {

    @GET("api/status")
    suspend fun getStatus(): StationStatus

    @GET("api/wfb/stats")
    suspend fun getWfbStats(): WfbStats

    @GET("api/video/stats")
    suspend fun getVideoStats(): VideoStats

    @POST("api/recording/start")
    suspend fun startRecording(): Response<Unit>

    @POST("api/recording/stop")
    suspend fun stopRecording(): Response<Unit>

    @GET("api/recording/list")
    suspend fun getRecordings(): List<RecordingInfo>

    @POST("api/camera/switch")
    suspend fun switchCamera(@Body body: CameraSwitchRequest): Response<Unit>

    @GET("api/system/info")
    suspend fun getSystemInfo(): SystemInfo

    @POST("api/system/reboot")
    suspend fun reboot(): Response<Unit>

    @POST("api/system/ota")
    suspend fun pushOta(@Body body: OtaRequest): Response<Unit>

    @GET("api/config")
    suspend fun getConfig(): StationConfig

    @PUT("api/config")
    suspend fun updateConfig(@Body config: StationConfig): Response<Unit>

    @GET("api/wfb/adapters")
    suspend fun getAdapters(): List<AdapterInfo>
}
