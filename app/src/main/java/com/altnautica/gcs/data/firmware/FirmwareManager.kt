package com.altnautica.gcs.data.firmware

import android.util.Log
import com.altnautica.gcs.data.groundstation.GroundStationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class FirmwareUpdate(
    val currentVersion: String,
    val newVersion: String,
    val downloadUrl: String,
    val releaseNotes: String = "",
    val sizeMb: Float = 0f,
)

enum class FirmwareState {
    IDLE,
    CHECKING,
    DOWNLOADING,
    PUSHING,
    REBOOTING,
    COMPLETE,
    ERROR,
}

/**
 * Manages firmware update checking and OTA push to the ADOS Ground Station.
 *
 * Flow: check for update -> download firmware -> POST to /api/system/ota -> reboot.
 * The ground station handles the actual flashing internally.
 */
@Singleton
class FirmwareManager @Inject constructor(
    private val repository: GroundStationRepository,
) {

    companion object {
        private const val TAG = "FirmwareManager"
        private const val FIRMWARE_CHECK_URL = "https://releases.altnautica.com/gs/latest.json"
    }

    private val _state = MutableStateFlow(FirmwareState.IDLE)
    val state: StateFlow<FirmwareState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _availableUpdate = MutableStateFlow<FirmwareUpdate?>(null)
    val availableUpdate: StateFlow<FirmwareUpdate?> = _availableUpdate.asStateFlow()

    /**
     * Check the ground station's current version against the latest release.
     * Returns a FirmwareUpdate if an update is available, null otherwise.
     */
    suspend fun checkForUpdate(currentVersion: String): FirmwareUpdate? {
        _state.value = FirmwareState.CHECKING
        _error.value = null

        return try {
            // Fetch latest version from release server
            val conn = java.net.URL(FIRMWARE_CHECK_URL)
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().readText()
                // Simple JSON parsing (avoid adding a dependency)
                val version = extractJsonField(json, "version") ?: currentVersion
                val url = extractJsonField(json, "url") ?: ""
                val notes = extractJsonField(json, "notes") ?: ""
                val size = extractJsonField(json, "size_mb")?.toFloatOrNull() ?: 0f

                if (version != currentVersion && url.isNotEmpty()) {
                    val update = FirmwareUpdate(
                        currentVersion = currentVersion,
                        newVersion = version,
                        downloadUrl = url,
                        releaseNotes = notes,
                        sizeMb = size,
                    )
                    _availableUpdate.value = update
                    _state.value = FirmwareState.IDLE
                    Log.i(TAG, "Update available: $currentVersion -> $version")
                    update
                } else {
                    _state.value = FirmwareState.IDLE
                    _availableUpdate.value = null
                    Log.i(TAG, "No update available (current: $currentVersion)")
                    null
                }
            } else {
                _state.value = FirmwareState.ERROR
                _error.value = "Check failed: HTTP ${conn.responseCode}"
                null
            }
        } catch (e: Exception) {
            _state.value = FirmwareState.ERROR
            _error.value = "Check failed: ${e.message}"
            Log.w(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Download firmware and push to the ground station via OTA API.
     */
    suspend fun downloadAndPush(update: FirmwareUpdate, onProgress: (Float) -> Unit) {
        _error.value = null

        try {
            // Phase 1: Download (0-50%)
            _state.value = FirmwareState.DOWNLOADING
            _progress.value = 0f

            // Simulate download progress (actual download happens on ground station side)
            for (i in 1..10) {
                kotlinx.coroutines.delay(200)
                val prog = i / 20f // 0 to 0.5
                _progress.value = prog
                onProgress(prog)
            }

            // Phase 2: Push to ground station (50-90%)
            _state.value = FirmwareState.PUSHING
            _progress.value = 0.5f
            onProgress(0.5f)

            val result = repository.pushOta(update.downloadUrl, update.newVersion)
            result.onFailure { e ->
                _state.value = FirmwareState.ERROR
                _error.value = "OTA push failed: ${e.message}"
                return
            }

            _progress.value = 0.9f
            onProgress(0.9f)

            // Phase 3: Reboot (90-100%)
            _state.value = FirmwareState.REBOOTING
            repository.reboot()

            _progress.value = 1f
            onProgress(1f)

            _state.value = FirmwareState.COMPLETE
            _availableUpdate.value = null
            Log.i(TAG, "OTA complete: ${update.newVersion}")
        } catch (e: Exception) {
            _state.value = FirmwareState.ERROR
            _error.value = "OTA failed: ${e.message}"
            Log.e(TAG, "OTA failed: ${e.message}", e)
        }
    }

    fun reset() {
        _state.value = FirmwareState.IDLE
        _progress.value = 0f
        _error.value = null
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(pattern).find(json)
        return match?.groupValues?.getOrNull(1)
    }
}
