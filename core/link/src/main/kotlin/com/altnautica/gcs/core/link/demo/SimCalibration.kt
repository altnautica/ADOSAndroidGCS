package com.altnautica.gcs.core.link.demo

import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.enums.AccelcalVehiclePos
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavResult
import com.altnautica.gcs.core.mavlink.enums.MavSeverity
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.messages.MagCalProgress
import com.altnautica.gcs.core.mavlink.messages.MagCalReport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Gyro, level, six-pose accelerometer and onboard compass calibration, ArduPilot style. */
internal class SimCalibration(private val sim: SimState) {
    private var accelPose: Int? = null
    private var magJob: Job? = null

    fun onPreflight(frame: MavlinkFrame, command: Int, p1: Float, p5: Double) {
        if (sim.armed) {
            sim.ack(frame, command, MavResult.MAV_RESULT_DENIED)
            return
        }
        when {
            p1 == 1f -> sim.scope.launch {
                sim.text(MavSeverity.MAV_SEVERITY_INFO, "Calibrating gyros")
                delay(500)
                sim.ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            }
            p5 == 2.0 -> sim.scope.launch {
                delay(300)
                sim.ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            }
            p5 == 1.0 -> {
                sim.ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                accelPose = 1
                requestPose(frame, 1)
            }
            else -> sim.ack(frame, command, MavResult.MAV_RESULT_UNSUPPORTED)
        }
    }

    fun onAccelPose(frame: MavlinkFrame, command: Int, pose: Int) {
        val expected = accelPose
        if (expected == null || pose != expected) {
            sim.ack(frame, command, MavResult.MAV_RESULT_DENIED)
            return
        }
        sim.ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
        if (pose < 6) {
            accelPose = pose + 1
            requestPose(frame, pose + 1)
        } else {
            accelPose = null
            sim.text(MavSeverity.MAV_SEVERITY_INFO, "Calibration successful")
            sim.send(
                CommandLong(
                    targetSystem = frame.sysId,
                    targetComponent = frame.compId,
                    command = MavCmd.MAV_CMD_ACCELCAL_VEHICLE_POS,
                    param1 = AccelcalVehiclePos.ACCELCAL_VEHICLE_POS_SUCCESS.toFloat(),
                ),
            )
        }
    }

    fun startMag(frame: MavlinkFrame, command: Int) {
        if (sim.armed) {
            sim.ack(frame, command, MavResult.MAV_RESULT_DENIED)
            return
        }
        sim.ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
        magJob?.cancel()
        magJob = sim.scope.launch {
            for (pct in 0..100 step 5) {
                sim.send(MagCalProgress(compassId = 0, calMask = 1, calStatus = if (pct < 50) 2 else 3, attempt = 1, completionPct = pct))
                delay(250)
            }
            sim.send(
                MagCalReport(
                    compassId = 0, calMask = 1, calStatus = 4, autosaved = 0, fitness = 8.5f,
                    ofsX = 12f, ofsY = -7f, ofsZ = 30f, diagX = 1f, diagY = 1f, diagZ = 1f, scaleFactor = 1f,
                ),
            )
        }
    }

    fun cancelMag() {
        magJob?.cancel()
    }

    private fun requestPose(frame: MavlinkFrame, pose: Int) {
        val names = listOf("level", "on its LEFT side", "on its RIGHT side", "nose DOWN", "nose UP", "on its BACK")
        sim.text(MavSeverity.MAV_SEVERITY_INFO, "Place vehicle ${names[pose - 1]} and press any key.")
        sim.send(CommandLong(targetSystem = frame.sysId, targetComponent = frame.compId, command = MavCmd.MAV_CMD_ACCELCAL_VEHICLE_POS, param1 = pose.toFloat()))
    }
}
