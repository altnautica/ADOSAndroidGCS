package com.altnautica.gcs.data.mavlink

import android.util.Log
import com.altnautica.gcs.data.telemetry.FlightMode
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavMode
import io.dronefleet.mavlink.common.SetMode
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MavLinkCommandSender @Inject constructor(
    private val repository: MavLinkRepository
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
