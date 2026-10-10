package com.altnautica.gcs.core.mavlink

import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.mavlink.modes.FlightModeTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FlightModeTablesTest {

    @Test
    fun `family follows autopilot and vehicle type`() {
        val ap = MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA
        assertEquals(FirmwareFamily.ArduCopter, FlightModeTables.family(ap, MavType.MAV_TYPE_QUADROTOR))
        assertEquals(FirmwareFamily.ArduPlane, FlightModeTables.family(ap, MavType.MAV_TYPE_FIXED_WING))
        assertEquals(FirmwareFamily.ArduPlane, FlightModeTables.family(ap, MavType.MAV_TYPE_VTOL_TILTROTOR))
        assertEquals(FirmwareFamily.ArduRover, FlightModeTables.family(ap, MavType.MAV_TYPE_GROUND_ROVER))
        assertEquals(FirmwareFamily.ArduSub, FlightModeTables.family(ap, MavType.MAV_TYPE_SUBMARINE))
        assertEquals(FirmwareFamily.Px4, FlightModeTables.family(MavAutopilot.MAV_AUTOPILOT_PX4, MavType.MAV_TYPE_QUADROTOR))
    }

    @Test
    fun `ardupilot numbers decode by family`() {
        assertEquals("LOITER", FlightModeTables.decode(FirmwareFamily.ArduCopter, 5).name)
        assertEquals("QLOITER", FlightModeTables.decode(FirmwareFamily.ArduPlane, 19).name)
        assertEquals("HOLD", FlightModeTables.decode(FirmwareFamily.ArduRover, 4).name)
        assertEquals("MODE_99", FlightModeTables.decode(FirmwareFamily.ArduCopter, 99).name)
    }

    @Test
    fun `px4 packs main mode in bits 16-23 and sub mode in bits 24-31`() {
        val mission = FlightModeTables.byName(FirmwareFamily.Px4, "AUTO")!!
        assertEquals((4L shl 16) or (4L shl 24), mission.customMode)
        assertEquals("AUTO", FlightModeTables.decode(FirmwareFamily.Px4, mission.customMode).name)
        assertEquals("ALT_HOLD", FlightModeTables.decode(FirmwareFamily.Px4, 2L shl 16).name)
        assertEquals("TERMINATION", FlightModeTables.decode(FirmwareFamily.Px4, 10L shl 16).name)
    }

    @Test
    fun `reported-only px4 states are not requestable`() {
        val names = FlightModeTables.available(FirmwareFamily.Px4).map { it.name }
        assertFalse("DESCEND" in names)
        assertFalse("TERMINATION" in names)
        assertNull(FlightModeTables.byName(FirmwareFamily.Px4, "DESCEND"))
    }
}
