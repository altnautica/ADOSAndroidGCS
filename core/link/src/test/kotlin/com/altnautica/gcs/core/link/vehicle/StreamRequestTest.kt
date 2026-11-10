package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.testing.ScriptedLink
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.messages.ExtendedSysState
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
}
