package com.altnautica.gcs.core.link.command

import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import com.altnautica.gcs.core.mavlink.enums.MavResult
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.messages.CommandAck
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.modes.FlightModeTables
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.VehicleCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandServiceTest {

    private fun service(link: ScriptedLink, scope: kotlinx.coroutines.CoroutineScope) =
        CommandService(scope, link, MutableStateFlow(null), MutableStateFlow(null))

    private val CommandLong.ack get() = CommandAck(command = command, result = MavResult.MAV_RESULT_ACCEPTED)

    @Test
    fun `accepted ack completes after one send`() = runTest {
        val link = ScriptedLink { m -> if (m is CommandLong) reply(m.ack) }
        val result = service(link, backgroundScope).run(VehicleCommand.Arm())
        assertEquals(CommandResult.Accepted, result)
        val sent = link.sent.single() as CommandLong
        assertEquals(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, sent.command)
        assertEquals(1f, sent.param1)
    }

    @Test
    fun `missing ack retries three times with confirmation incremented then times out`() = runTest {
        val link = ScriptedLink()
        val start = testScheduler.currentTime
        val result = service(link, backgroundScope).run(VehicleCommand.Land)
        assertEquals(CommandResult.Timeout, result)
        val sends = link.sent.filterIsInstance<CommandLong>()
        assertEquals(listOf(0, 1, 2, 3), sends.map { it.confirmation })
        assertEquals(4 * CommandService.ACK_TIMEOUT_MS, testScheduler.currentTime - start)
    }

    @Test
    fun `ack on the second attempt is accepted`() = runTest {
        var attempts = 0
        val link = ScriptedLink { m -> if (m is CommandLong && ++attempts == 2) reply(m.ack) }
        assertEquals(CommandResult.Accepted, service(link, backgroundScope).run(VehicleCommand.Disarm()))
        assertEquals(2, link.sent.size)
    }

    @Test
    fun `in progress waits for the final ack without resending`() = runTest {
        lateinit var self: ScriptedLink
        self = ScriptedLink { m ->
            if (m is CommandLong) {
                reply(CommandAck(command = m.command, result = MavResult.MAV_RESULT_IN_PROGRESS, progress = 10))
                backgroundScope.launch {
                    delay(5_000)
                    self.reply(CommandAck(command = m.command, result = MavResult.MAV_RESULT_ACCEPTED))
                }
            }
        }
        val result = service(self, backgroundScope).run(VehicleCommand.Arm())
        assertEquals(CommandResult.Accepted, result)
        assertEquals(1, self.sent.size)
    }

    @Test
    fun `in progress without a final ack times out after ten seconds`() = runTest {
        val link = ScriptedLink { m ->
            if (m is CommandLong) reply(CommandAck(command = m.command, result = MavResult.MAV_RESULT_IN_PROGRESS))
        }
        val start = testScheduler.currentTime
        assertEquals(CommandResult.Timeout, service(link, backgroundScope).run(VehicleCommand.Arm()))
        assertEquals(CommandService.IN_PROGRESS_TIMEOUT_MS, testScheduler.currentTime - start)
        assertEquals(1, link.sent.size)
    }

    @Test
    fun `rejection maps to Rejected with the result code`() = runTest {
        val link = ScriptedLink { m ->
            if (m is CommandLong) reply(CommandAck(command = m.command, result = MavResult.MAV_RESULT_DENIED))
        }
        val r = service(link, backgroundScope).run(VehicleCommand.Arm())
        assertEquals(CommandResult.Rejected(MavResult.MAV_RESULT_DENIED, "denied"), r)
    }

    @Test
    fun `acks for other commands are ignored`() = runTest {
        val link = ScriptedLink { m ->
            if (m is CommandLong) {
                reply(CommandAck(command = MavCmd.MAV_CMD_NAV_TAKEOFF, result = MavResult.MAV_RESULT_ACCEPTED))
                reply(m.ack)
            }
        }
        assertEquals(CommandResult.Accepted, service(link, backgroundScope).run(VehicleCommand.Arm()))
    }

    @Test
    fun `set mode uses DO_SET_MODE with the custom mode flag`() = runTest {
        val link = ScriptedLink { m -> if (m is CommandLong) reply(m.ack) }
        val loiter = FlightMode(5, "LOITER")
        assertEquals(CommandResult.Accepted, service(link, backgroundScope).run(VehicleCommand.SetMode(loiter)))
        val sent = link.sent.single() as CommandLong
        assertEquals(MavCmd.MAV_CMD_DO_SET_MODE, sent.command)
        assertEquals(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED.toFloat(), sent.param1)
        assertEquals(5f, sent.param2)
    }

    @Test
    fun `px4 set mode sends main and sub mode`() = runTest {
        val px4 = VehicleTarget(1, 1, MavAutopilot.MAV_AUTOPILOT_PX4, MavType.MAV_TYPE_QUADROTOR)
        val link = ScriptedLink(px4) { m -> if (m is CommandLong) reply(m.ack) }
        val mission = FlightModeTables.byName(px4.family, "AUTO")!!
        service(link, backgroundScope).run(VehicleCommand.SetMode(mission))
        val sent = link.sent.single() as CommandLong
        assertEquals(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED.toFloat(), sent.param1)
        assertEquals(4f, sent.param2)
        assertEquals(4f, sent.param3)
    }

    @Test
    fun `kill force-disarms with the magic value`() = runTest {
        val link = ScriptedLink { m -> if (m is CommandLong) reply(m.ack) }
        service(link, backgroundScope).run(VehicleCommand.Kill)
        val sent = link.sent.single() as CommandLong
        assertEquals(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, sent.command)
        assertEquals(0f, sent.param1)
        assertEquals(21196f, sent.param2)
    }

    @Test
    fun `takeoff on ardupilot switches to guided first`() = runTest {
        val link = ScriptedLink { m -> if (m is CommandLong) reply(m.ack) }
        assertEquals(CommandResult.Accepted, service(link, backgroundScope).run(VehicleCommand.Takeoff(12f)))
        val sent = link.sent.filterIsInstance<CommandLong>()
        assertEquals(listOf(MavCmd.MAV_CMD_DO_SET_MODE, MavCmd.MAV_CMD_NAV_TAKEOFF), sent.map { it.command })
        assertEquals(4f, sent[0].param2)
        assertEquals(12f, sent[1].param7)
    }
}
