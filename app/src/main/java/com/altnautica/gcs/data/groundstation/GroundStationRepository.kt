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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroundStationRepository @Inject constructor(
    private val api: GroundStationApi
) {

    companion object {
        private const val TAG = "GroundStationRepo"
        private const val POLL_INTERVAL_MS = 2000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null

    private val _status = MutableStateFlow(StationStatus())
    val status: StateFlow<StationStatus> = _status.asStateFlow()

    private val _wfbStats = MutableStateFlow(WfbStats())
    val wfbStats: StateFlow<WfbStats> = _wfbStats.asStateFlow()

    private val _videoStats = MutableStateFlow(VideoStats())
    val videoStats: StateFlow<VideoStats> = _videoStats.asStateFlow()

    private val _systemInfo = MutableStateFlow(SystemInfo())
    val systemInfo: StateFlow<SystemInfo> = _systemInfo.asStateFlow()

    private val _config = MutableStateFlow(StationConfig())
    val config: StateFlow<StationConfig> = _config.asStateFlow()

    private val _reachable = MutableStateFlow(false)
    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    fun startPolling() {
        stopPolling()
        pollingJob = scope.launch {
            while (isActive) {
                pollStats()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun pollStats() {
        val statusResult = runApi { api.getStatus() }
        statusResult.onSuccess {
            _status.value = it
            _reachable.value = true
        }.onFailure {
            _reachable.value = false
        }

        if (_reachable.value) {
            runApi { api.getWfbStats() }.onSuccess { _wfbStats.value = it }
            runApi { api.getVideoStats() }.onSuccess { _videoStats.value = it }
        }
    }

    suspend fun fetchSystemInfo(): Result<SystemInfo> {
        return runApi { api.getSystemInfo() }.also { result ->
            result.onSuccess { _systemInfo.value = it }
        }
    }

    suspend fun fetchConfig(): Result<StationConfig> {
        return runApi { api.getConfig() }.also { result ->
            result.onSuccess { _config.value = it }
        }
    }

    suspend fun updateConfig(config: StationConfig): Result<Unit> {
        return runApi { api.updateConfig(config) }.map { }
    }

    suspend fun startRecording(): Result<Unit> {
        return runApi { api.startRecording() }.map { }
    }

    suspend fun stopRecording(): Result<Unit> {
        return runApi { api.stopRecording() }.map { }
    }

    suspend fun getRecordings(): Result<List<RecordingInfo>> {
        return runApi { api.getRecordings() }
    }

    suspend fun switchCamera(cameraId: String): Result<Unit> {
        return runApi { api.switchCamera(CameraSwitchRequest(cameraId)) }.map { }
    }

    suspend fun reboot(): Result<Unit> {
        return runApi { api.reboot() }.map { }
    }

    suspend fun pushOta(firmwareUrl: String, version: String): Result<Unit> {
        return runApi { api.pushOta(OtaRequest(firmwareUrl, version)) }.map { }
    }

    private suspend fun <T> runApi(call: suspend () -> T): Result<T> {
        return try {
            Result.success(call())
        } catch (e: Exception) {
            Log.w(TAG, "API call failed: ${e.message}")
            Result.failure(e)
        }
    }
}
