package com.altnautica.gcs.core.link.calibration

import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.param.ParamValue
import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioChannelsToWriteTest {
    private fun ch(n: Int, min: Int, max: Int) = RcChannelRange(n, (min + max) / 2, min, max)
    private val sticks = RadioSticks(listOf(1, 2, 3, 4), throttle = 3)

    private fun params(vararg p: Pair<String, Int>) = p.associate { (k, v) -> k to ParamValue(k, v.toDouble(), 9, 0) }

    @Test
    fun untouchedChannelsAreNotWritten() {
        val channels = listOf(ch(1, 1000, 2000), ch(2, 1000, 2000), ch(3, 1000, 2000), ch(4, 1000, 2000), ch(5, 1500, 1500), ch(6, 1490, 1510))
        assertEquals(listOf(1, 2, 3, 4), MavlinkCalibrationService.radioChannelsToWrite(channels, sticks)!!.map { it.channel })
    }

    @Test
    fun aStickThatBarelyMovedRefusesTheSave() {
        val channels = listOf(ch(1, 1000, 2000), ch(2, 1000, 2000), ch(3, 1400, 1550), ch(4, 1000, 2000))
        assertNull(MavlinkCalibrationService.radioChannelsToWrite(channels, sticks))
    }

    @Test
    fun aMissingStickChannelRefusesTheSave() {
        assertNull(MavlinkCalibrationService.radioChannelsToWrite(listOf(ch(1, 1000, 2000)), sticks))
    }

    @Test
    fun remappedSticksAreChecked() {
        val channels = listOf(ch(1, 1500, 1500), ch(5, 1000, 2000), ch(6, 1000, 2000), ch(7, 1000, 2000), ch(8, 1000, 2000))
        assertEquals(
            listOf(5, 6, 7, 8),
            MavlinkCalibrationService.radioChannelsToWrite(channels, RadioSticks(listOf(5, 6, 7, 8)))!!.map { it.channel },
        )
    }

    @Test
    fun stickMapsFollowTheFirmwareAndVehicleClass() {
        val copter = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)
        val px4 = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_PX4, MavType.MAV_TYPE_QUADROTOR)
        val boat = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_SURFACE_BOAT)
        val ap = params("RCMAP_ROLL" to 1, "RCMAP_PITCH" to 2, "RCMAP_THROTTLE" to 3, "RCMAP_YAW" to 4)

        assertEquals(RadioSticks(listOf(1, 2, 3, 4), throttle = 3), MavlinkCalibrationService.radioSticks(copter, ap))
        assertEquals(RadioSticks(listOf(1, 3)), MavlinkCalibrationService.radioSticks(boat, ap))
        assertNull("PX4 does not read RCMAP_*", MavlinkCalibrationService.radioSticks(px4, ap))
        assertEquals(
            RadioSticks(listOf(2, 1), throttle = 1),
            MavlinkCalibrationService.radioSticks(px4, params("RC_MAP_ROLL" to 2, "RC_MAP_PITCH" to 0, "RC_MAP_THROTTLE" to 1, "RC_MAP_YAW" to 0)),
        )
        assertNull(MavlinkCalibrationService.radioSticks(copter, params("RCMAP_ROLL" to 1)))
    }
}
