package com.altnautica.gcs.core.agent.groundstation

import com.altnautica.gcs.core.agent.http.AgentHttp
import com.altnautica.gcs.core.agent.http.AgentHttpException
import com.altnautica.gcs.core.agent.pairing.NotPairedError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the ground-station REST surface served by the agent under
 * /api/v1/ground-station, plus the agent-wide supervisor restart.
 *
 * Every call targets the base URL configured at call time. A non-2xx answer
 * throws: 401 as [NotPairedError], anything else as [AgentHttpException]
 * (camera switch maps 501 to [CameraNotSupportedError]). Transport failures
 * surface as `IOException`.
 */
@Singleton
class GroundStationClient @Inject constructor(
    private val http: AgentHttp,
) {

    private companion object {
        const val BASE = "api/v1/ground-station"
    }

    suspend fun getStatus(): StationStatus =
        http.decode(http.send("GET", "$BASE/status"), StationStatus.serializer())

    suspend fun getWfb(): WfbConfig =
        http.decode(http.send("GET", "$BASE/wfb"), WfbConfig.serializer())

    /** Store a new radio channel; the agent persists only the channel. */
    suspend fun putWfb(update: WfbUpdate): WfbWriteResult =
        http.decode(
            http.send("PUT", "$BASE/wfb", WfbUpdate.serializer(), update),
            WfbWriteResult.serializer(),
        )

    suspend fun getNetwork(): NetworkConfig =
        http.decode(http.send("GET", "$BASE/network"), NetworkConfig.serializer())

    suspend fun putNetworkAp(update: ApUpdate): ApConfig =
        http.decode(
            http.send("PUT", "$BASE/network/ap", ApUpdate.serializer(), update),
            ApConfig.serializer(),
        )

    suspend fun startRecording(body: RecordingStartRequest): RecordingStartResponse =
        http.decode(
            http.send("POST", "$BASE/recording/start", RecordingStartRequest.serializer(), body),
            RecordingStartResponse.serializer(),
        )

    suspend fun stopRecording(): RecordingStopResponse =
        http.decode(http.send("POST", "$BASE/recording/stop"), RecordingStopResponse.serializer())

    suspend fun listRecordings(): RecordingListResponse =
        http.decode(http.send("GET", "$BASE/recording/list"), RecordingListResponse.serializer())

    /**
     * Cycle the agent's own service tree (`ados-*` units). Not a machine
     * reboot — the agent serves no OS-reboot route, so this is the strongest
     * recovery action reachable over HTTP.
     */
    suspend fun restartSupervisor() {
        http.requireSuccess(http.send("POST", "api/v1/system/restart-supervisor"))
    }

    suspend fun switchCamera(body: CameraSwitchRequest): CameraSwitchResponse {
        val response = http.send("POST", "$BASE/camera/switch", CameraSwitchRequest.serializer(), body)
        if (response.code == 501) {
            throw CameraNotSupportedError("drone does not advertise multi-camera support")
        }
        return http.decode(response, CameraSwitchResponse.serializer())
    }
}
