package com.altnautica.gcs.data.mavlink

import android.util.Log
import com.altnautica.gcs.data.agriculture.Waypoint
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavFrame
import io.dronefleet.mavlink.common.MavMissionType
import io.dronefleet.mavlink.common.MissionCount
import io.dronefleet.mavlink.common.MissionItemInt
import io.dronefleet.mavlink.common.MissionClearAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MavLinkMissionUploader @Inject constructor(
    private val repository: MavLinkRepository,
) {

    companion object {
        private const val TAG = "MavLinkMissionUploader"
        private const val GCS_SYS_ID = 255
        private const val GCS_COMP_ID = 190
        private const val TARGET_SYS_ID = 1
        private const val TARGET_COMP_ID = 1
    }

    private val _uploadProgress = MutableStateFlow(0f)
    val uploadProgress: StateFlow<Float> = _uploadProgress.asStateFlow()

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    /**
     * Uploads a list of waypoints to the flight controller using
     * the MAVLink mission protocol (MISSION_COUNT + MISSION_ITEM_INT).
     *
     * Item mapping:
     *   - Index 0: MAV_CMD_NAV_TAKEOFF (cmd 22)
     *   - Last index: MAV_CMD_NAV_RETURN_TO_LAUNCH (cmd 20)
     *   - All others: MAV_CMD_NAV_WAYPOINT (cmd 16)
     *
     * Coordinates use MISSION_ITEM_INT (lat/lon as int32 * 1e7).
     * Frame is MAV_FRAME_GLOBAL_RELATIVE_ALT (altitude relative to home).
     */
    suspend fun uploadMission(waypoints: List<Waypoint>) {
        if (waypoints.isEmpty()) {
            _uploadState.value = UploadState.Error("No waypoints to upload")
            return
        }

        _uploadState.value = UploadState.Uploading
        _uploadProgress.value = 0f

        try {
            // Step 1: Send MISSION_COUNT to tell the FC how many items to expect
            sendMissionCount(waypoints.size)

            // Step 2: Send each MISSION_ITEM_INT
            for ((index, wp) in waypoints.withIndex()) {
                val command = when {
                    index == 0 -> MavCmd.MAV_CMD_NAV_TAKEOFF
                    index == waypoints.size - 1 -> MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH
                    else -> MavCmd.MAV_CMD_NAV_WAYPOINT
                }

                sendMissionItemInt(
                    seq = index,
                    command = command,
                    lat = wp.lat,
                    lon = wp.lon,
                    alt = wp.alt,
                )

                _uploadProgress.value = (index + 1).toFloat() / waypoints.size
                Log.d(TAG, "Sent item $index/${waypoints.size}: ${command.name} " +
                    "lat=${wp.lat} lon=${wp.lon} alt=${wp.alt}")
            }

            _uploadState.value = UploadState.Complete
            Log.i(TAG, "Mission upload complete: ${waypoints.size} items")
        } catch (e: Exception) {
            Log.e(TAG, "Mission upload failed: ${e.message}", e)
            _uploadState.value = UploadState.Error(e.message ?: "Upload failed")
        }
    }

    /**
     * Clears the current mission on the flight controller
     * by sending MAV_CMD_MISSION_CLEAR_ALL via COMMAND_LONG.
     */
    suspend fun clearMission() {
        try {
            val msg = MissionClearAll.builder()
                .targetSystem(TARGET_SYS_ID)
                .targetComponent(TARGET_COMP_ID)
                .build()
            sendPayload(msg)
            Log.i(TAG, "Mission clear sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear mission: ${e.message}", e)
        }
    }

    fun resetState() {
        _uploadState.value = UploadState.Idle
        _uploadProgress.value = 0f
    }

    private suspend fun sendMissionCount(count: Int) {
        val msg = MissionCount.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .count(count)
            .missionType(MavMissionType.MAV_MISSION_TYPE_MISSION)
            .build()
        sendPayload(msg)
    }

    private suspend fun sendMissionItemInt(
        seq: Int,
        command: MavCmd,
        lat: Double,
        lon: Double,
        alt: Float,
    ) {
        val msg = MissionItemInt.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .seq(seq)
            .frame(MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT)
            .command(command)
            .current(if (seq == 0) 1 else 0)
            .autocontinue(1)
            .param1(0f) // hold time (0 = fly through)
            .param2(0f) // acceptance radius
            .param3(0f) // pass radius (0 = through WP)
            .param4(Float.NaN) // yaw (NaN = don't care)
            .x((lat * 1e7).toInt())
            .y((lon * 1e7).toInt())
            .z(alt)
            .missionType(MavMissionType.MAV_MISSION_TYPE_MISSION)
            .build()
        sendPayload(msg)
    }

    private suspend fun sendPayload(payload: Any) {
        val outputStream = ByteArrayOutputStream()
        val connection = MavlinkConnection.create(
            ByteArray(0).inputStream(),
            outputStream,
        )
        connection.send2(GCS_SYS_ID, GCS_COMP_ID, payload)
        val bytes = outputStream.toByteArray()
        if (bytes.isNotEmpty()) {
            repository.sendBytes(bytes)
        }
    }
}

sealed class UploadState {
    data object Idle : UploadState()
    data object Uploading : UploadState()
    data object Complete : UploadState()
    data class Error(val message: String) : UploadState()
}
