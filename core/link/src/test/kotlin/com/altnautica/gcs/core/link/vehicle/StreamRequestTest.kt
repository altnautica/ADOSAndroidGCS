package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.MavlinkFrameParser
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavState
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.messages.ExtendedSysState
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamRequestTest {

    private fun ScriptedLink.intervalRequests() =
        sent.filterIsInstance<CommandLong>().filter { it.command == MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL }

    @Test
    fun `connect asks for the landed state at 1 Hz`() = runTest {
        val target = VehicleTarget(1, 1, 3, 2)
        val link = ScriptedLink(target)
        link.requestStreams(target)
        val req = link.intervalRequests().single()
        assertEquals(1, req.targetSystem.toInt())
        assertEquals(1, req.targetComponent.toInt())
        assertEquals(ExtendedSysState.ID.toFloat(), req.param1)
        assertEquals(1_000_000f, req.param2)
    }

    @Test
    fun `a px4 vehicle also gets the landed state request`() = runTest {
        val target = VehicleTarget(1, 1, 12, 2)
        val link = ScriptedLink(target)
        link.requestStreams(target)
        assertEquals(1, link.intervalRequests().size)
    }

    @Test
    fun `a flight controller heard again after a reboot is asked for the landed state again`() = runTest {
        val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
        val written = mutableListOf<MavlinkFrame>()
        val transport = object : MavlinkByteTransport {
            override val state: StateFlow<TransportState> = MutableStateFlow(TransportState.Open)
            override val incoming: Flow<ByteArray> = incoming
            override suspend fun write(bytes: ByteArray): WriteResult {
                written += MavlinkFrameParser().parse(bytes)
                return WriteResult.Sent
            }
            override suspend fun close() = Unit
        }
        val vehicle = MavlinkVehicle(DeviceId("fc-reboot"), backgroundScope, ElapsedClock { testScheduler.currentTime }, activity = null)
        vehicle.link.attach(transport)
        runCurrent()
        val fc = MavlinkFrameWriter(sysId = 1, compId = 1)
        val heartbeat = Heartbeat(
            type = MavType.MAV_TYPE_QUADROTOR,
            autopilot = MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,
            baseMode = 0,
            customMode = 0,
            systemStatus = MavState.MAV_STATE_STANDBY,
            mavlinkVersion = 3,
        )
        suspend fun beat() {
            incoming.emit(fc.encode(heartbeat))
            runCurrent()
        }
        fun intervals() = written.filter { it.msgId == CommandLong.ID }
            .count { CommandLong.decode(it.payload).command == MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL }

        beat()
        assertEquals(1, intervals())
        repeat(3) {
            advanceTimeBy(1_000)
            beat()
        }
        assertEquals("a steady link asks once", 1, intervals())
        // The flight controller reboots: its heartbeats stop for longer than the silence window.
        advanceTimeBy(8_000)
        beat()
        assertEquals(2, intervals())
    }
}
