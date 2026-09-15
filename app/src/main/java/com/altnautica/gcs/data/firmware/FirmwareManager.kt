package com.altnautica.gcs.data.firmware

import android.util.Log
import androidx.annotation.StringRes
import com.altnautica.gcs.R
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
    COMPLETE,
    ERROR,
}

/**
 * Checks whether a newer ground-station release exists and reports it.
 *
 * **Applying it is a named gap, not a feature of this app.** The agent serves
 * no OTA route on any profile — not a drone one, not a ground-station one — so
 * there is nothing here to push an image to. What this used to do instead was
 * animate a fabricated download bar for two seconds and then call a repository
 * stub that always failed, which reports a state that is known to be false.
 *
 * To close the gap the agent needs `GET /api/ota` plus
 * `POST /api/ota/{check,install,restart}` driving the same path as the `ados
 * update` CLI; this class then gains a push call against it. Until then the
 * update is applied on the node and this surface says so.
 */
@Singleton
class FirmwareManager @Inject constructor() {

    companion object {
        private const val TAG = "FirmwareManager"
        private const val FIRMWARE_CHECK_URL = "https://releases.altnautica.com/gs/latest.json"
    }

    private val _state = MutableStateFlow(FirmwareState.IDLE)
    val state: StateFlow<FirmwareState> = _state.asStateFlow()

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
     * Where an available update is applied.
     *
     * Not a no-op install button: offering one that cannot work is worse than
     * saying plainly that the image is applied on the node.
     */
    @StringRes
    fun applyInstructionRes(): Int = R.string.firmware_apply_on_node

    fun reset() {
        _state.value = FirmwareState.IDLE
        _error.value = null
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\""
        val match = Regex(pattern).find(json)
        return match?.groupValues?.getOrNull(1)
    }
}
