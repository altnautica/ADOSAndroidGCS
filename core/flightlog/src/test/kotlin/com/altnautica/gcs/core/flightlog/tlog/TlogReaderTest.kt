package com.altnautica.gcs.core.flightlog.tlog

import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.messages.VfrHud
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TlogReaderTest {
    private val vehicle = MavlinkFrameWriter(sysId = 1, compId = 1)
    private val t0 = 1_760_000_000_000_000L

    private fun log(order: ByteOrder, vararg records: Pair<Long, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        records.forEach { (us, frame) ->
            out.write(ByteBuffer.allocate(8).order(order).putLong(us).array())
            out.write(frame)
        }
        return out.toByteArray()
    }

    private fun v(m: MavMessage) = vehicle.encode(m)

    @Test
    fun readsBigEndianAndLegacyLittleEndianLogs() {
        val frames = arrayOf(t0 to v(Heartbeat(type = 2, autopilot = 3)), t0 + 500_000 to v(VfrHud(groundspeed = 4f)))
        for (order in listOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val records = TlogReader(log(order, *frames).inputStream()).asSequence().toList()
            assertEquals(listOf(t0, t0 + 500_000), records.map { it.timeUs })
            assertArrayEquals(frames[1].second, records[1].frame)
        }
    }

    @Test
    fun truncatedTailEndsAtLastWholeRecord() {
        val bytes = log(ByteOrder.BIG_ENDIAN, t0 to v(Heartbeat(type = 2, autopilot = 3)), t0 + 1 to v(VfrHud()))
        val cut = bytes.copyOf(bytes.size - 4)
        assertEquals(1, TlogReader(cut.inputStream()).asSequence().count())
    }
}
