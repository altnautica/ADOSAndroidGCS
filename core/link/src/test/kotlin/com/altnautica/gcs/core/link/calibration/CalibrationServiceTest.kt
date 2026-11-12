package com.altnautica.gcs.core.link.calibration

import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.param.ParamService
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavResult
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.messages.CommandAck
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.messages.Statustext
import com.altnautica.gcs.core.model.CommandResult
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationServiceTest {

    private val px4 = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_PX4, MavType.MAV_TYPE_QUADROTOR)
    private val ardupilot = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)

    private fun ackingLink(target: VehicleTarget) = ScriptedLink(target) { m ->
        if (m is CommandLong) reply(CommandAck(command = m.command, result = MavResult.MAV_RESULT_ACCEPTED))
    }

    private fun service(link: ScriptedLink, scope: CoroutineScope) = MavlinkCalibrationService(
        scope,
        link,
        CommandService(scope, link, MutableStateFlow(null), MutableStateFlow(null)),
        mockk<ParamService>(relaxed = true),
    )

    @Test
    fun `px4 gyro stays running after the start ack and fails on the cal text`() = runTest {
        val link = ackingLink(px4)
        val cal = service(link, backgroundScope)

        assertEquals(CommandResult.Accepted, cal.startGyro())
        assertTrue(cal.state.value is CalibrationState.Running)

        link.reply(Statustext(severity = 3, text = "[cal] calibration failed: gyro"))
        runCurrent()
        assertEquals(CalibrationState.Failed(CalibrationKind.Gyro, "[cal] calibration failed: gyro"), cal.state.value)
    }

    @Test
    fun `px4 level succeeds on the cal done text`() = runTest {
        val link = ackingLink(px4)
        val cal = service(link, backgroundScope)

        cal.startLevel()
        link.reply(Statustext(severity = 6, text = "[cal] progress <50>"))
        runCurrent()
        assertTrue(cal.state.value is CalibrationState.Running)

        link.reply(Statustext(severity = 6, text = "[cal] calibration done: level"))
        runCurrent()
        assertEquals(CalibrationState.Succeeded(CalibrationKind.Level, "[cal] calibration done: level"), cal.state.value)
    }

    @Test
    fun `ardupilot gyro succeeds on the ack`() = runTest {
        val link = ackingLink(ardupilot)
        val cal = service(link, backgroundScope)

        cal.startGyro()
        assertTrue(cal.state.value is CalibrationState.Succeeded)
    }

    private fun ScriptedLink.lastLong() = sent.filterIsInstance<CommandLong>().last()

    @Test
    fun `px4 compass uses preflight calibration and follows the cal texts`() = runTest {
        val link = ackingLink(px4)
        val cal = service(link, backgroundScope)

        assertEquals(CommandResult.Accepted, cal.startCompass())
        val sent = link.lastLong()
        assertEquals(MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION, sent.command)
        assertEquals(1f, sent.param2)
        assertEquals(CalibrationState.Running(CalibrationKind.Compass, null), cal.state.value)

        link.reply(Statustext(severity = 6, text = "[cal] progress <40>"))
        runCurrent()
        assertEquals(CalibrationState.Running(CalibrationKind.Compass, "[cal] progress <40>"), cal.state.value)

        link.reply(Statustext(severity = 6, text = "[cal] calibration done: mag"))
        runCurrent()
        assertEquals(CalibrationState.Succeeded(CalibrationKind.Compass, "[cal] calibration done: mag"), cal.state.value)
    }

    @Test
    fun `ardupilot compass still uses the onboard mag cal`() = runTest {
        val link = ackingLink(ardupilot)
        service(link, backgroundScope).startCompass()
        assertEquals(MavCmd.MAV_CMD_DO_START_MAG_CAL, link.lastLong().command)
    }

    @Test
    fun `esc airspeed and compassmot send their preflight slots`() = runTest {
        val link = ackingLink(ardupilot)
        val cal = service(link, backgroundScope)

        assertEquals(CommandResult.Accepted, cal.startEsc())
        assertEquals(1f, link.lastLong().param7)
        assertEquals(CalibrationState.Succeeded(CalibrationKind.Esc, null), cal.state.value)

        cal.startAirspeed()
        assertEquals(2f, link.lastLong().param6)

        assertEquals(CommandResult.Accepted, cal.startCompassMot())
        assertEquals(MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION, link.lastLong().command)
        assertEquals(1f, link.lastLong().param6)
        assertEquals(CalibrationState.Succeeded(CalibrationKind.CompassMot, null), cal.state.value)
    }

    @Test
    fun `compassmot is refused off ArduCopter without sending`() = runTest {
        val link = ackingLink(px4)
        assertEquals(CommandResult.Unsupported, service(link, backgroundScope).startCompassMot())
        assertTrue(link.sent.none { it is CommandLong })
    }
}
