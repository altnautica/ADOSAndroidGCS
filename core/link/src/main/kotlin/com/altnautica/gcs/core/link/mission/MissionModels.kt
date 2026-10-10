package com.altnautica.gcs.core.link.mission

import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.enums.MavMissionType
import com.altnautica.gcs.core.model.CommandResult
import kotlinx.coroutines.flow.StateFlow

/** MAVLink `mission_type`. */
enum class MissionType(val wire: Int) {
    Mission(MavMissionType.MAV_MISSION_TYPE_MISSION),
    Fence(MavMissionType.MAV_MISSION_TYPE_FENCE),
    Rally(MavMissionType.MAV_MISSION_TYPE_RALLY),
}

/**
 * One mission, fence or rally item as carried by `MISSION_ITEM_INT`.
 *
 * In global frames [x]/[y] are latitude/longitude in degrees × 1e7 and [z] is
 * the altitude in metres (relative to home for `MAV_FRAME_GLOBAL_RELATIVE_ALT_INT`).
 * Commands in `MAV_FRAME_MISSION` use [x], [y], [z] as their params 5-7.
 * The sequence number is the item's index in the list.
 */
data class MissionItem(
    val command: Int,
    val frame: Int = MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
    val param1: Float = 0f,
    val param2: Float = 0f,
    val param3: Float = 0f,
    val param4: Float = 0f,
    val x: Int = 0,
    val y: Int = 0,
    val z: Float = 0f,
    val autocontinue: Boolean = true,
) {
    val latDeg: Double get() = x / 1e7
    val lonDeg: Double get() = y / 1e7

    companion object {
        /** Item at a global position with the altitude relative to home. */
        fun global(
            command: Int,
            latDeg: Double,
            lonDeg: Double,
            altM: Float,
            param1: Float = 0f,
            param2: Float = 0f,
            param3: Float = 0f,
            param4: Float = 0f,
            frame: Int = MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
        ) = MissionItem(
            command = command,
            frame = frame,
            param1 = param1,
            param2 = param2,
            param3 = param3,
            param4 = param4,
            x = Math.round(latDeg * 1e7).toInt(),
            y = Math.round(lonDeg * 1e7).toInt(),
            z = altM,
        )
    }
}

/**
 * Items as the operator sees them. On ArduPilot the vehicle's seq 0 is the
 * home position: it is reported here as [home] and never appears in [items];
 * uploads write a home placeholder at seq 0 themselves.
 */
data class MissionPlan(val home: MissionItem?, val items: List<MissionItem>)

enum class TransferDirection { Upload, Download, Clear }

sealed interface MissionTransfer {
    data object Idle : MissionTransfer
    data class InProgress(val type: MissionType, val direction: TransferDirection, val done: Int, val total: Int) : MissionTransfer
    data class Completed(val type: MissionType, val direction: TransferDirection) : MissionTransfer
    data class Failed(val type: MissionType, val direction: TransferDirection, val error: MissionError) : MissionTransfer
}

sealed interface MissionError {
    data object NoLink : MissionError
    data object Busy : MissionError
    data object Timeout : MissionError
    data class WriteFailed(val reason: String) : MissionError

    /** `MISSION_ACK` with a non-accepted `MAV_MISSION_RESULT` code. */
    data class Rejected(val code: Int) : MissionError
}

sealed interface MissionResult<out T> {
    data class Success<T>(val value: T) : MissionResult<T>
    data class Failure(val error: MissionError) : MissionResult<Nothing>
}

/** Mission, fence and rally transfer with the vehicle's mission protocol. */
interface MissionService {
    val transfer: StateFlow<MissionTransfer>

    suspend fun upload(type: MissionType, items: List<MissionItem>): MissionResult<Unit>

    suspend fun download(type: MissionType): MissionResult<MissionPlan>

    suspend fun clear(type: MissionType): MissionResult<Unit>

    /** Makes operator item [index] (0-based within [MissionPlan.items]) the current one. */
    suspend fun setCurrent(index: Int): CommandResult

    /** `MAV_CMD_MISSION_START`: switches to the autopilot's mission mode and runs the stored mission. */
    suspend fun start(): CommandResult
}
