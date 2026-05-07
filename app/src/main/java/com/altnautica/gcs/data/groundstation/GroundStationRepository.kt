package com.altnautica.gcs.data.groundstation

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marker exception surfaced when the agent reports that the paired
 * drone does not advertise multi-camera support (HTTP 501 on
 * /camera/switch). Lets the UI distinguish a capability gap from a
 * transport-level failure.
 */
class CameraNotSupportedError(message: String) : Exception(message)

/**
 * Shared state holder for the ground-station REST surface.
 *
 * Polls /api/v1/ground-station/status at 2 Hz to keep the link, network,
 * system, and role views warm for the UI. The wfb radio config and the
 * full network view are fetched on demand via fetchWfb()/fetchNetwork().
 *
 * Recording (start/stop/list) and camera switch hit the agent's REST
 * surface directly. System reboot and OTA push are still stubs and
 * return Result.failure with a stable NotImplementedError marker so the
 * UI can surface a friendly hint without crashing.
 *
 * Camera switch returns HTTP 501 on single-camera drones; the
 * repository swallows that as a [CameraNotSupportedError] result and
 * the UI shows a capability notice rather than treating it as a
 * transport failure.
 */
@Singleton
class GroundStationRepository @Inject constructor(
    private val api: GroundStationApi
) {

    companion object {
        private const val TAG = "GroundStationRepo"
        private const val POLL_INTERVAL_MS = 2000L

        private val NOT_IMPLEMENTED = UnsupportedOperationException(
            "endpoint not implemented in this agent profile"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    private val _status = MutableStateFlow(StationStatus())
    val status: StateFlow<StationStatus> = _status.asStateFlow()

    private val _wfb = MutableStateFlow(WfbConfig())
    val wfb: StateFlow<WfbConfig> = _wfb.asStateFlow()

    private val _network = MutableStateFlow(NetworkConfig())
    val network: StateFlow<NetworkConfig> = _network.asStateFlow()

    private val _reachable = MutableStateFlow(false)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    fun startPolling() {
        stopPolling()
        pollingJob = scope.launch {
            while (isActive) {
                pollStatus()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun pollStatus() {
        runApi { api.getStatus() }
            .onSuccess {
                _status.value = it
                _reachable.value = true
            }
            .onFailure {
                _reachable.value = false
            }
    }

    suspend fun fetchWfb(): Result<WfbConfig> {
        return runApi { api.getWfb() }.also { result ->
            result.onSuccess { _wfb.value = it }
        }
    }

    suspend fun updateWfb(update: WfbUpdate): Result<WfbConfig> {
        return runApi { api.putWfb(update) }.also { result ->
            result.onSuccess { _wfb.value = it }
        }
    }

    suspend fun fetchNetwork(): Result<NetworkConfig> {
        return runApi { api.getNetwork() }.also { result ->
            result.onSuccess { _network.value = it }
        }
    }

    suspend fun updateNetworkAp(update: ApUpdate): Result<ApConfig> {
        return try {
            val response = api.putNetworkAp(update)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                Result.failure(IllegalStateException("ap update failed: HTTP ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.w(TAG, "AP update failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Start a server-side recording on the ground node. The optional
     * [filenameHint] is a stem; the agent appends a timestamp and the
     * .mp4 extension. Returns the resolved filename, an ISO-8601 UTC
     * start time, and the absolute path on disk.
     */
    suspend fun startRecording(filenameHint: String? = null): Result<RecordingStartResponse> =
        runApi { api.startRecording(RecordingStartRequest(filenameHint = filenameHint)) }

    /**
     * Stop the in-flight recording. Returns the filename, the stop
     * time, the duration in seconds, and the resulting size in bytes.
     */
    suspend fun stopRecording(): Result<RecordingStopResponse> =
        runApi { api.stopRecording() }

    /** Listing of recordings on disk, plus the active-recording flag. */
    suspend fun listRecordings(): Result<RecordingListResponse> =
        runApi { api.listRecordings() }

    /**
     * Ask the paired drone to switch to [cameraId]. On a single-camera
     * drone the agent returns HTTP 501; the failure carries
     * [CameraNotSupportedError] so the UI can surface a capability
     * notice rather than treating it as a network error.
     */
    suspend fun switchCamera(cameraId: String): Result<CameraSwitchResponse> {
        return try {
            val response = api.switchCamera(CameraSwitchRequest(cameraId = cameraId))
            val body = response.body()
            when {
                response.isSuccessful && body != null -> Result.success(body)
                response.code() == 501 -> Result.failure(
                    CameraNotSupportedError(
                        "drone does not advertise multi-camera support",
                    )
                )
                else -> Result.failure(
                    IllegalStateException("camera switch failed: HTTP ${response.code()}")
                )
            }
        } catch (e: HttpException) {
            if (e.code() == 501) {
                Result.failure(
                    CameraNotSupportedError(
                        "drone does not advertise multi-camera support",
                    )
                )
            } else {
                Log.w(TAG, "Camera switch failed: ${e.message}")
                Result.failure(e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Camera switch failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** Stub: no system reboot endpoint on this agent profile yet. */
    suspend fun reboot(): Result<Unit> = Result.failure(NOT_IMPLEMENTED)

    /** Stub: no OTA push endpoint on this agent profile yet. */
    suspend fun pushOta(
        @Suppress("UNUSED_PARAMETER") firmwareUrl: String,
        @Suppress("UNUSED_PARAMETER") version: String,
    ): Result<Unit> = Result.failure(NOT_IMPLEMENTED)

    private suspend fun <T> runApi(call: suspend () -> T): Result<T> {
        return try {
            Result.success(call())
        } catch (e: Exception) {
            Log.w(TAG, "API call failed: ${e.message}")
            Result.failure(e)
        }
    }
}
