package com.altnautica.gcs.core.link.calibration

import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.param.ParamLoadResult
import com.altnautica.gcs.core.link.param.ParamService
import com.altnautica.gcs.core.link.param.ParamSetResult
import com.altnautica.gcs.core.link.param.ParamSnapshot
import com.altnautica.gcs.core.link.param.ParamValue
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavResult
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.messages.CommandAck
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.messages.RcChannels
import com.altnautica.gcs.core.mavlink.messages.Statustext
import com.altnautica.gcs.core.model.CommandResult
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationServiceTest {

    private val px4 = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_PX4, MavType.MAV_TYPE_QUADROTOR)
    private val ardupilot = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_QUADROTOR)
    private val arduplane = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_FIXED_WING)
    private val rover = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, MavType.MAV_TYPE_GROUND_ROVER)
    private val px4Plane = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_PX4, MavType.MAV_TYPE_FIXED_WING)
    private val px4Rover = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_PX4, MavType.MAV_TYPE_GROUND_ROVER)

    private fun ackingLink(target: VehicleTarget) = ScriptedLink(target) { m ->
        if (m is CommandLong) reply(CommandAck(command = m.command, result = MavResult.MAV_RESULT_ACCEPTED))
    }

    private fun service(
        link: ScriptedLink,
        scope: CoroutineScope,
        params: ParamService = mockk<ParamService>(relaxed = true),
    ) = MavlinkCalibrationService(
        scope,
        link,
        CommandService(scope, link, MutableStateFlow(null), MutableStateFlow(null)),
        params,
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
    fun `esc on ardupilot is refused without sending the px4 slot`() = runTest {
        // ArduPilot has no p7 handler: it answers UNSUPPORTED to the PX4 ESC slot.
        val link = ScriptedLink(ardupilot) { m ->
            if (m is CommandLong) {
                val result = if (m.param7 == 1f) MavResult.MAV_RESULT_UNSUPPORTED else MavResult.MAV_RESULT_ACCEPTED
                reply(CommandAck(command = m.command, result = result))
            }
        }
        val cal = service(link, backgroundScope)

        assertEquals(CommandResult.Unsupported, cal.startEsc())
        assertTrue(link.sent.none { it is CommandLong })
        assertEquals(CalibrationState.Idle, cal.state.value)
    }

    @Test
    fun `esc on px4 sends p7 and follows the cal texts`() = runTest {
        val link = ackingLink(px4)
        val cal = service(link, backgroundScope)

        assertEquals(CommandResult.Accepted, cal.startEsc())
        assertEquals(1f, link.lastLong().param7)
        assertEquals(CalibrationState.Running(CalibrationKind.Esc, null), cal.state.value)
    }

    @Test
    fun `airspeed on arduplane sends p3 and waits through in progress for the result`() = runTest {
        // ArduPlane calibrates airspeed with the barometer (p3=1): IN_PROGRESS, then the result.
        val link = ScriptedLink(arduplane) { m ->
            if (m is CommandLong) {
                val result = if (m.param3 == 1f) MavResult.MAV_RESULT_IN_PROGRESS else MavResult.MAV_RESULT_UNSUPPORTED
                reply(CommandAck(command = m.command, result = result))
            }
        }
        val cal = service(link, backgroundScope)

        val started = async { cal.startAirspeed() }
        runCurrent()
        val sent = link.lastLong()
        assertEquals(MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION, sent.command)
        assertEquals(1f, sent.param3)
        assertEquals(0f, sent.param6)
        assertEquals(CalibrationState.Running(CalibrationKind.Airspeed, null), cal.state.value)

        link.reply(CommandAck(command = MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION, result = MavResult.MAV_RESULT_ACCEPTED))
        assertEquals(CommandResult.Accepted, started.await())
        assertEquals(CalibrationState.Succeeded(CalibrationKind.Airspeed, null), cal.state.value)
        assertEquals(1, link.sent.count { it is CommandLong })
    }

    @Test
    fun `airspeed on px4 keeps the p6 slot`() = runTest {
        val link = ackingLink(px4Plane)
        service(link, backgroundScope).startAirspeed()
        assertEquals(2f, link.lastLong().param6)
        assertEquals(0f, link.lastLong().param3)
    }

    @Test
    fun `cancelling a px4 compass calibration sends the all zero cancel`() = runTest {
        val link = ackingLink(px4)
        val cal = service(link, backgroundScope)
        cal.startCompass()

        cal.cancel()
        val sent = link.lastLong()
        assertEquals(2, link.sent.count { it is CommandLong })
        assertEquals(MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION, sent.command)
        assertEquals(
            listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f),
            listOf(sent.param1, sent.param2, sent.param3, sent.param4, sent.param5, sent.param6, sent.param7),
        )
        assertEquals(CalibrationState.Idle, cal.state.value)
    }

    /** Radio capture where [moved] channels sweep 1000-2000 and end centred; every other channel rests at 1500. */
    private suspend fun TestScope.capture(link: ScriptedLink, cal: CalibrationService, moved: Set<Int>) {
        cal.startRadio()
        for (v in listOf(1000, 2000, 1500)) {
            val ch = IntArray(8) { if (it + 1 in moved) v else 1500 }
            link.reply(
                RcChannels(
                    chancount = 8, chan1Raw = ch[0], chan2Raw = ch[1], chan3Raw = ch[2], chan4Raw = ch[3],
                    chan5Raw = ch[4], chan6Raw = ch[5], chan7Raw = ch[6], chan8Raw = ch[7],
                ),
            )
            runCurrent()
        }
    }

    @Test
    fun `px4 radio save checks the RC_MAP sticks and skips unmapped axes`() = runTest {
        val link = ScriptedLink(px4)
        val params = RecordingParams(mapOf("RC_MAP_ROLL" to 5.0, "RC_MAP_PITCH" to 6.0, "RC_MAP_THROTTLE" to 7.0, "RC_MAP_YAW" to 0.0))
        val cal = service(link, backgroundScope, params)
        capture(link, cal, setOf(5, 6, 7))

        assertEquals(CommandResult.Accepted, cal.saveRadio())
        assertEquals(setOf(5, 6, 7), params.writes.map { it.first.removePrefix("RC").substringBefore('_').toInt() }.toSet())
        assertTrue("throttle trims to its minimum", "RC7_TRIM" to 1000.0 in params.writes)
        assertEquals(CalibrationState.Succeeded(CalibrationKind.Radio, null), cal.state.value)
    }

    @Test
    fun `ardupilot rover radio save needs only steering and throttle`() = runTest {
        val link = ScriptedLink(rover)
        val params = RecordingParams(mapOf("RCMAP_ROLL" to 1.0, "RCMAP_PITCH" to 2.0, "RCMAP_THROTTLE" to 3.0, "RCMAP_YAW" to 4.0))
        val cal = service(link, backgroundScope, params)
        capture(link, cal, setOf(1, 3))

        assertEquals(CommandResult.Accepted, cal.saveRadio())
        assertEquals(setOf(1, 3), params.writes.map { it.first.removePrefix("RC").substringBefore('_').toInt() }.toSet())
        assertTrue("a rover throttle trims to its resting centre", "RC3_TRIM" to 1500.0 in params.writes)
    }

    @Test
    fun `px4 rover radio save needs two of its mapped sticks`() = runTest {
        val link = ScriptedLink(px4Rover)
        val params = RecordingParams(mapOf("RC_MAP_ROLL" to 1.0, "RC_MAP_PITCH" to 2.0, "RC_MAP_THROTTLE" to 3.0, "RC_MAP_YAW" to 4.0))
        val cal = service(link, backgroundScope, params)
        capture(link, cal, setOf(2))
        assertEquals(CommandResult.Rejected(-1, "sticksNotMoved"), cal.saveRadio())

        capture(link, cal, setOf(2, 4))
        assertEquals(CommandResult.Accepted, cal.saveRadio())
    }

    @Test
    fun `radio save is refused without writing when the stick map is not loaded`() = runTest {
        val link = ScriptedLink(ardupilot)
        val params = RecordingParams(emptyMap())
        val cal = service(link, backgroundScope, params)
        capture(link, cal, setOf(1, 2, 3, 4))

        assertEquals(CommandResult.Rejected(-1, "rcMapNotLoaded"), cal.saveRadio())
        assertTrue(params.writes.isEmpty())
        assertTrue("the capture stays open", cal.state.value is CalibrationState.Radio)
    }

    /** Parameters held in memory; every write is recorded and confirmed. */
    private class RecordingParams(values: Map<String, Double>) : ParamService {
        val writes = mutableListOf<Pair<String, Double>>()
        override val snapshot = MutableStateFlow(
            values.entries.mapIndexed { i, (k, v) -> k to ParamValue(k, v, 9, i) }.toMap()
                .let { ParamSnapshot(it.size, it.size, it) },
        )
        override val loading = MutableStateFlow(false)

        override suspend fun refresh() = ParamLoadResult.Complete(snapshot.value.count)

        override suspend fun set(name: String, value: Double): ParamSetResult {
            writes += name to value
            return ParamSetResult.Confirmed(ParamValue(name, value, 9, 0))
        }

        override suspend fun read(name: String) = snapshot.value.values[name]
    }
}
