package com.altnautica.gcs

import com.altnautica.gcs.data.mavlink.CommandQueue
import com.altnautica.gcs.data.mavlink.MavLinkCommandSender
import com.altnautica.gcs.data.mavlink.MavLinkRepository
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.ardupilotmega.ArdupilotmegaDialect
import io.dronefleet.mavlink.ardupilotmega.FencePoint
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.ParamSet
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Verifies the FENCE_POINT upload protocol emits the right MAVLink
 * message sequence:
 *   1. MAV_CMD_DO_FENCE_ENABLE param1=0 (disable)
 *   2. PARAM_SET FENCE_TOTAL = N
 *   3. FENCE_POINT idx=0..N-1
 *   4. MAV_CMD_DO_FENCE_ENABLE param1=1 (enable)
 */
class MavLinkFencePointTest {

    @Test
    fun `sendFencePoints emits disable, total, vertices, enable`() = runTest {
        val captured = mutableListOf<ByteArray>()
        val repo = mockk<MavLinkRepository>(relaxed = true)
        coEvery { repo.sendBytes(any()) } answers {
            val arg = firstArg<ByteArray>()
            captured.add(arg.copyOf())
        }

        val queue = CommandQueue()
        val sender = MavLinkCommandSender(repo, queue)

        val points = listOf(
            12.9716 to 77.5946, // Bangalore
            12.9722 to 77.5950,
            12.9725 to 77.5955,
        )
        sender.sendFencePoints(points)

        // Decode every captured frame.
        val payloads = captured.flatMap { decodeFrame(it) }

        // First message: COMMAND_LONG(207, 0) - disable
        val first = payloads.first()
        assertTrue("first should be CommandLong", first is CommandLong)
        val disable = first as CommandLong
        assertEquals(207, disable.command().value())
        assertEquals(0f, disable.param1(), 0.001f)

        // Second: PARAM_SET FENCE_TOTAL = 3
        val second = payloads[1]
        assertTrue("second should be ParamSet", second is ParamSet)
        val total = second as ParamSet
        assertEquals("FENCE_TOTAL", total.paramId())
        assertEquals(3f, total.paramValue(), 0.001f)

        // Then 3 FENCE_POINT messages.
        val fencePoints = payloads.filterIsInstance<FencePoint>()
        assertEquals(3, fencePoints.size)
        for ((expectedIdx, fp) in fencePoints.withIndex()) {
            assertEquals(expectedIdx, fp.idx())
            assertEquals(3, fp.count())
            val (expLat, expLng) = points[expectedIdx]
            assertEquals(expLat.toFloat(), fp.lat(), 1e-4f)
            assertEquals(expLng.toFloat(), fp.lng(), 1e-4f)
        }

        // Last: COMMAND_LONG(207, 1) - re-enable
        val lastEnable = payloads.filterIsInstance<CommandLong>()
            .last { it.command().value() == 207 }
        assertNotNull(lastEnable)
        assertEquals(1f, lastEnable.param1(), 0.001f)
    }

    @Test
    fun `sendFencePoints with empty list does nothing`() = runTest {
        val sentSlot = slot<ByteArray>()
        val repo = mockk<MavLinkRepository>(relaxed = true)
        coJustRun { repo.sendBytes(capture(sentSlot)) }
        val sender = MavLinkCommandSender(repo, CommandQueue())

        sender.sendFencePoints(emptyList())
        // Nothing should have been captured.
        assertTrue("no payloads emitted", !sentSlot.isCaptured)
    }

    /**
     * Decode a single MAVLink frame to its payload list. The encoder emits one
     * frame per send, so most calls return a single-element list.
     */
    private fun decodeFrame(bytes: ByteArray): List<Any> {
        val out = mutableListOf<Any>()
        // Default dialect is COMMON only. Register ardupilotmega so FencePoint
        // (an ArduPilot-flavored message) decodes back to its concrete type.
        val conn = MavlinkConnection.builder(
            ByteArrayInputStream(bytes),
            ByteArrayOutputStream(),
        )
            .defaultDialect(ArdupilotmegaDialect())
            .dialect(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, ArdupilotmegaDialect())
            .build()
        while (true) {
            val msg = try {
                conn.next() ?: break
            } catch (_: Throwable) {
                break
            }
            out.add(msg.payload)
        }
        return out
    }
}
