package com.altnautica.gcs.core.agent.groundstation

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The ground-station surface under `/api/v1/ground-station`. On a node of
 * another profile every call answers [com.altnautica.gcs.core.agent.http.AgentError.ProfileMismatch].
 */
class GroundStationApi internal constructor(private val client: AgentClient) {

    suspend fun status(): AgentResult<StationStatus> = client.get("$BASE/status", StationStatus.serializer())

    suspend fun wfb(): AgentResult<WfbConfig> = client.get("$BASE/wfb", WfbConfig.serializer())

    /** Store a new radio channel. The agent accepts exactly `{channel, force?}`. */
    suspend fun putWfb(channel: Int, force: Boolean = false): AgentResult<WfbWriteResult> =
        client.send(
            "PUT",
            "$BASE/wfb",
            WfbUpdate.serializer(),
            WfbUpdate(channel = channel, force = force.takeIf { it }),
            WfbWriteResult.serializer(),
        )

    suspend fun network(): AgentResult<NetworkConfig> = client.get("$BASE/network", NetworkConfig.serializer())

    suspend fun putNetworkAp(update: ApUpdate): AgentResult<ApConfig> =
        client.send("PUT", "$BASE/network/ap", ApUpdate.serializer(), update, ApConfig.serializer(), slow = true)

    suspend fun modem(): AgentResult<JsonObject> = client.get("$BASE/network/modem", JsonObject.serializer())

    suspend fun putModem(body: JsonObject): AgentResult<JsonElement> =
        client.sendJson("PUT", "$BASE/network/modem", body, JsonElement.serializer(), slow = true)

    suspend fun scanClientNetworks(): AgentResult<JsonElement> =
        client.get("$BASE/network/client/scan", JsonElement.serializer())

    suspend fun joinClientNetwork(body: JsonObject): AgentResult<JsonElement> =
        client.sendJson("PUT", "$BASE/network/client/join", body, JsonElement.serializer(), slow = true)

    suspend fun leaveClientNetwork(): AgentResult<JsonElement> =
        client.sendJson("DELETE", "$BASE/network/client", null, JsonElement.serializer())

    suspend fun role(): AgentResult<JsonObject> = client.get("$BASE/role", JsonObject.serializer())

    suspend fun putRole(body: JsonObject): AgentResult<JsonElement> =
        client.sendJson("PUT", "$BASE/role", body, JsonElement.serializer(), slow = true)

    suspend fun mesh(): AgentResult<JsonObject> = client.get("$BASE/mesh", JsonObject.serializer())

    suspend fun pairPending(): AgentResult<JsonElement> = client.get("$BASE/pair/pending", JsonElement.serializer())

    suspend fun pairAccept(body: JsonObject? = null): AgentResult<JsonElement> =
        client.sendJson("POST", "$BASE/pair/accept", body, JsonElement.serializer())

    suspend fun pairClose(): AgentResult<JsonElement> =
        client.sendJson("POST", "$BASE/pair/close", null, JsonElement.serializer())

    suspend fun pairApprove(deviceId: String): AgentResult<JsonElement> =
        client.sendJson("POST", "$BASE/pair/approve/${AgentClient.segment(deviceId)}", null, JsonElement.serializer())

    suspend fun pairRevoke(deviceId: String): AgentResult<JsonElement> =
        client.sendJson("POST", "$BASE/pair/revoke/${AgentClient.segment(deviceId)}", null, JsonElement.serializer())

    suspend fun pic(): AgentResult<JsonObject> = client.get("$BASE/pic", JsonObject.serializer())

    suspend fun picClaim(body: JsonObject): AgentResult<JsonObject> =
        client.sendJson("POST", "$BASE/pic/claim", body, JsonObject.serializer())

    suspend fun picRelease(body: JsonObject): AgentResult<JsonObject> =
        client.sendJson("POST", "$BASE/pic/release", body, JsonObject.serializer())

    suspend fun recordings(): AgentResult<RecordingListResponse> =
        client.get("$BASE/recording/list", RecordingListResponse.serializer())

    suspend fun startRecording(filenameHint: String? = null): AgentResult<RecordingStartResponse> =
        client.send(
            "POST",
            "$BASE/recording/start",
            RecordingStartRequest.serializer(),
            RecordingStartRequest(filenameHint),
            RecordingStartResponse.serializer(),
        )

    suspend fun stopRecording(): AgentResult<RecordingStopResponse> =
        client.sendJson("POST", "$BASE/recording/stop", null, RecordingStopResponse.serializer())

    /**
     * Switch the carried drone's camera. `accepted` is null while the flight
     * controller's acknowledgement is not observed; a drone without multi-camera
     * support answers [com.altnautica.gcs.core.agent.http.AgentError.FeatureAbsent] (501).
     */
    suspend fun switchCamera(cameraId: String): AgentResult<CameraSwitchResponse> =
        client.send(
            "POST",
            "$BASE/camera/switch",
            CameraSwitchRequest.serializer(),
            CameraSwitchRequest(cameraId),
            CameraSwitchResponse.serializer(),
        )

    private companion object {
        const val BASE = "api/v1/ground-station"
    }
}
