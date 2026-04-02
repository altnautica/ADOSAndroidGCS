package com.altnautica.gcs.data.mavlink

import android.util.Log
import com.altnautica.gcs.data.telemetry.FlightMode
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavMode
import io.dronefleet.mavlink.common.SetMode
import io.dronefleet.mavlink.util.EnumValue
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MavLinkCommandSender @Inject constructor(
    private val repository: MavLinkRepository,
    private val commandQueue: CommandQueue
) {

    companion object {
        private const val TAG = "MavLinkCommandSender"
        private const val GCS_SYS_ID = 255
        private const val GCS_COMP_ID = 190
        private const val TARGET_SYS_ID = 1
        private const val TARGET_COMP_ID = 1
    }

    suspend fun sendSetMode(mode: FlightMode) {
        Log.d(TAG, "Sending SET_MODE: ${mode.label}")
        val msg = SetMode.builder()
            .targetSystem(TARGET_SYS_ID)
            .baseMode(MavMode.MAV_MODE_STABILIZE_ARMED) // MAV_MODE_FLAG_CUSTOM_MODE_ENABLED baseline
            .customMode(mode.modeNumber.toLong())
            .build()
        sendMessage(msg)
    }

    suspend fun sendArm() {
        Log.d(TAG, "Sending ARM")
        sendCommandLong(
            command = MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
            param1 = 1f // 1 = arm
        )
    }

    suspend fun sendDisarm() {
        Log.d(TAG, "Sending DISARM")
        sendCommandLong(
            command = MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
            param1 = 0f // 0 = disarm
        )
    }

    suspend fun sendForceDisarm() {
        Log.d(TAG, "Sending FORCE DISARM")
        sendCommandLong(
            command = MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
            param1 = 0f,
            param2 = 21196f // magic force-disarm value
        )
    }

    suspend fun sendRtl() {
        sendSetMode(FlightMode.RTL)
    }

    suspend fun sendRebootAutopilot() {
        Log.d(TAG, "Sending REBOOT")
        sendCommandLong(
            command = MavCmd.MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN,
            param1 = 1f // 1 = reboot autopilot
        )
    }

    suspend fun sendTakeoff(altitude: Float) {
        Log.d(TAG, "Sending TAKEOFF alt=$altitude")
        sendCommandLong(
            command = MavCmd.MAV_CMD_NAV_TAKEOFF,
            param7 = altitude // altitude in meters
        )
    }

    suspend fun sendLand() {
        Log.d(TAG, "Sending LAND")
        sendCommandLong(
            command = MavCmd.MAV_CMD_NAV_LAND
        )
    }

    suspend fun sendPauseMission() {
        Log.d(TAG, "Sending PAUSE")
        sendCommandLong(
            command = MavCmd.MAV_CMD_DO_PAUSE_CONTINUE,
            param1 = 0f // 0 = pause
        )
    }

    suspend fun sendResumeMission() {
        Log.d(TAG, "Sending RESUME")
        sendCommandLong(
            command = MavCmd.MAV_CMD_DO_PAUSE_CONTINUE,
            param1 = 1f // 1 = resume
        )
    }

    suspend fun sendOrbit(radius: Float, velocity: Float, lat: Double, lon: Double, alt: Float) {
        Log.d(TAG, "Sending ORBIT r=$radius v=$velocity")
        // MAV_CMD_DO_ORBIT = 34, may not be in dronefleet enum
        sendCommandLongRaw(
            commandId = 34,
            param1 = radius,
            param2 = velocity,
            param5 = lat.toFloat(),
            param6 = lon.toFloat(),
            param7 = alt
        )
    }

    suspend fun sendCalibrationAccel() {
        Log.d(TAG, "Sending ACCEL CAL")
        sendCommandLong(
            command = MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION,
            param5 = 1f // accel cal
        )
    }

    suspend fun sendCalibrationLevel() {
        Log.d(TAG, "Sending LEVEL CAL")
        sendCommandLong(
            command = MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION,
            param5 = 2f // simple accel cal (level)
        )
    }

    suspend fun sendStartMagCal() {
        Log.d(TAG, "Sending MAG CAL START")
        // MAV_CMD_DO_START_MAG_CAL = 42424, ArduPilot-specific
        sendCommandLongRaw(
            commandId = 42424,
            param1 = 0f, // mag_mask (0 = all)
            param2 = 1f, // retry on failure
            param3 = 1f, // autosave
            param4 = 0f  // delay
        )
    }

    suspend fun sendSetRoi(lat: Double, lon: Double, alt: Float) {
        Log.d(TAG, "Sending SET ROI lat=$lat lon=$lon alt=$alt")
        // MAV_CMD_DO_SET_ROI_LOCATION = 195
        sendCommandLongRaw(
            commandId = 195,
            param5 = lat.toFloat(),
            param6 = lon.toFloat(),
            param7 = alt
        )
    }

    suspend fun sendClearRoi() {
        Log.d(TAG, "Sending CLEAR ROI")
        // MAV_CMD_DO_SET_ROI_NONE = 197
        sendCommandLongRaw(commandId = 197)
    }

    suspend fun sendCameraTrigger() {
        Log.d(TAG, "Sending CAMERA TRIGGER")
        sendCommandLong(
            command = MavCmd.MAV_CMD_DO_DIGICAM_CONTROL,
            param5 = 1f // shoot
        )
    }

    suspend fun sendGimbalPitchYaw(pitch: Float, yaw: Float) {
        Log.d(TAG, "Sending GIMBAL pitch=$pitch yaw=$yaw")
        // MAV_CMD_DO_GIMBAL_MANAGER_PITCHYAW = 1000
        sendCommandLongRaw(
            commandId = 1000,
            param1 = pitch,
            param2 = yaw,
            param3 = Float.NaN, // pitch rate (NaN = use angle)
            param4 = Float.NaN, // yaw rate
            param5 = 0f // flags
        )
    }

    suspend fun sendParamRequestList() {
        Log.d(TAG, "Sending PARAM_REQUEST_LIST")
        val msg = io.dronefleet.mavlink.common.ParamRequestList.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .build()
        sendMessage(msg)
    }

    suspend fun sendParamSet(paramId: String, value: Float, type: Int) {
        Log.d(TAG, "Sending PARAM_SET $paramId=$value type=$type")
        val msg = io.dronefleet.mavlink.common.ParamSet.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .paramId(paramId)
            .paramValue(value)
            .paramType(
                EnumValue.of(io.dronefleet.mavlink.common.MavParamType.entry(type))
            )
            .build()
        sendMessage(msg)
    }

    suspend fun sendManualControl(x: Int, y: Int, z: Int, r: Int, buttons: Int) {
        val msg = io.dronefleet.mavlink.common.ManualControl.builder()
            .target(TARGET_SYS_ID)
            .x(x)
            .y(y)
            .z(z)
            .r(r)
            .buttons(buttons)
            .build()
        sendMessage(msg)
    }

    suspend fun sendHeartbeat() {
        val msg = io.dronefleet.mavlink.minimal.Heartbeat.builder()
            .type(io.dronefleet.mavlink.minimal.MavType.MAV_TYPE_GCS)
            .autopilot(io.dronefleet.mavlink.minimal.MavAutopilot.MAV_AUTOPILOT_INVALID)
            .baseMode(io.dronefleet.mavlink.minimal.MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED)
            .customMode(0)
            .systemStatus(io.dronefleet.mavlink.minimal.MavState.MAV_STATE_ACTIVE)
            .mavlinkVersion(3)
            .build()
        sendMessage(msg)
    }

    /**
     * Send a command with ACK verification via CommandQueue.
     * Returns CommandResult with success/failure and MAV_RESULT value.
     * Retries automatically on timeout (default 3 retries, 3s timeout each).
     */
    suspend fun sendCommandWithAck(
        command: MavCmd,
        param1: Float = 0f,
        param2: Float = 0f,
        param3: Float = 0f,
        param4: Float = 0f,
        param5: Float = 0f,
        param6: Float = 0f,
        param7: Float = 0f,
        maxRetries: Int = 3,
        timeoutMs: Long = 3000
    ): CommandQueue.CommandResult {
        return commandQueue.send(
            commandId = command.value(),
            maxRetries = maxRetries,
            timeoutMs = timeoutMs
        ) {
            sendCommandLong(command, param1, param2, param3, param4, param5, param6, param7)
        }
    }

    /**
     * Arm with ACK verification and retry.
     */
    suspend fun sendArmWithAck(): CommandQueue.CommandResult {
        Log.d(TAG, "Sending ARM (with ACK)")
        return sendCommandWithAck(
            command = MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
            param1 = 1f
        )
    }

    /**
     * Disarm with ACK verification and retry.
     */
    suspend fun sendDisarmWithAck(): CommandQueue.CommandResult {
        Log.d(TAG, "Sending DISARM (with ACK)")
        return sendCommandWithAck(
            command = MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
            param1 = 0f
        )
    }

    /**
     * Takeoff with ACK verification.
     */
    suspend fun sendTakeoffWithAck(altitude: Float): CommandQueue.CommandResult {
        Log.d(TAG, "Sending TAKEOFF (with ACK) alt=$altitude")
        return sendCommandWithAck(
            command = MavCmd.MAV_CMD_NAV_TAKEOFF,
            param7 = altitude
        )
    }

    private suspend fun sendCommandLong(
        command: MavCmd,
        param1: Float = 0f,
        param2: Float = 0f,
        param3: Float = 0f,
        param4: Float = 0f,
        param5: Float = 0f,
        param6: Float = 0f,
        param7: Float = 0f
    ) {
        val msg = CommandLong.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .command(command)
            .confirmation(0)
            .param1(param1)
            .param2(param2)
            .param3(param3)
            .param4(param4)
            .param5(param5)
            .param6(param6)
            .param7(param7)
            .build()
        sendMessage(msg)
    }

    /**
     * Send a COMMAND_LONG using a raw integer command ID.
     * Used for commands that may not exist in the dronefleet MavCmd enum
     * (ArduPilot-specific or newer MAVLink extensions).
     */
    suspend fun sendCommandLongRaw(
        commandId: Int,
        param1: Float = 0f,
        param2: Float = 0f,
        param3: Float = 0f,
        param4: Float = 0f,
        param5: Float = 0f,
        param6: Float = 0f,
        param7: Float = 0f
    ) {
        val msg = CommandLong.builder()
            .targetSystem(TARGET_SYS_ID)
            .targetComponent(TARGET_COMP_ID)
            .command(EnumValue.of(commandId))
            .confirmation(0)
            .param1(param1)
            .param2(param2)
            .param3(param3)
            .param4(param4)
            .param5(param5)
            .param6(param6)
            .param7(param7)
            .build()
        sendMessage(msg)
    }

    private suspend fun sendMessage(payload: Any) {
        try {
            val outputStream = ByteArrayOutputStream()
            val connection = MavlinkConnection.create(
                ByteArray(0).inputStream(),
                outputStream
            )
            connection.send2(
                GCS_SYS_ID,
                GCS_COMP_ID,
                payload
            )
            val bytes = outputStream.toByteArray()
            if (bytes.isNotEmpty()) {
                repository.sendBytes(bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to encode/send message: ${e.message}")
        }
    }
}
