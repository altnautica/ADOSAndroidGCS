package com.altnautica.gcs.feature.logs.tlog

import app.cash.turbine.test
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.messages.GlobalPositionInt
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.messages.SysStatus
import com.altnautica.gcs.core.mavlink.messages.VfrHud
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TlogTest {
    private val vehicle = MavlinkFrameWriter(sysId = 1, compId = 1)
    private val gcs = MavlinkFrameWriter()
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

    @Test
    fun seriesComeFromTheVehicleOnly() {
        val bytes = log(
            ByteOrder.BIG_ENDIAN,
            t0 to gcs.encode(Heartbeat(type = 6, autopilot = 8)),
            t0 + 100_000 to v(Heartbeat(type = 2, autopilot = 3)),
            t0 + 1_000_000 to v(GlobalPositionInt(lat = 473_977_420, lon = 85_455_940, relativeAlt = 12_500)),
            t0 + 1_000_000 to gcs.encode(VfrHud(groundspeed = 99f)),
            t0 + 2_000_000 to v(VfrHud(groundspeed = 6.5f)),
            t0 + 3_000_000 to v(SysStatus(batteryRemaining = 87)),
            t0 + 4_000_000 to v(SysStatus(batteryRemaining = -1)),
        )
        val s = FlightSeries.read(bytes.inputStream())
        assertEquals(listOf(SeriesPoint(1f, 12.5f)), s.altitudeRelM)
        assertEquals(listOf(SeriesPoint(2f, 6.5f)), s.groundspeedMs)
        assertEquals(listOf(SeriesPoint(3f, 87f)), s.batteryPct)
        assertEquals(47.397742, s.track.single().latDeg, 1e-9)
        assertEquals(4f, s.durationS)
    }

    @Test
    fun replayEmitsFramesAndRefusesWrites() = runTest {
        val frames = listOf(v(Heartbeat(type = 2, autopilot = 3)), v(VfrHud(groundspeed = 1f)))
        val bytes = log(ByteOrder.BIG_ENDIAN, t0 to frames[0], t0 + 1_000_000 to frames[1])
        val replay = TlogReplayTransport({ bytes.inputStream() }, speed = 10f)
        replay.incoming.test {
            assertArrayEquals(frames[0], awaitItem())
            assertArrayEquals(frames[1], awaitItem())
            awaitComplete()
        }
        assertEquals(TransportState.Closed(TlogReplayTransport.END_OF_LOG), replay.state.value)
        assertEquals(WriteResult.ReceiveOnly, replay.write(byteArrayOf(1)))
    }
}
