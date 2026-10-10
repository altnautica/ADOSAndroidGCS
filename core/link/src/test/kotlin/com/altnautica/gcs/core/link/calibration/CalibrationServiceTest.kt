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
}
