package com.altnautica.gcs.core.mavlink

import com.altnautica.gcs.core.mavlink.messages.Attitude
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.Statustext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MavlinkFramingTest {

    private val vehicle = MavlinkFrameWriter(sysId = 1, compId = 1)

    @Test
    fun `frame split across three chunks parses once`() {
        val frame = vehicle.encode(Attitude(timeBootMs = 1000, roll = 0.1f, pitch = -0.2f, yaw = 1.5f))
        val parser = MavlinkFrameParser()
        val out = mutableListOf<MavlinkFrame>()
        val a = frame.size / 3
        val b = 2 * frame.size / 3
        parser.feed(frame.copyOfRange(0, a)) { out += it }
        assertEquals(0, out.size)
        parser.feed(frame.copyOfRange(a, b)) { out += it }
        assertEquals(0, out.size)
        parser.feed(frame.copyOfRange(b, frame.size)) { out += it }
        assertEquals(1, out.size)
        val decoded = out.single().decode() as Attitude
        assertEquals(1.5f, decoded.yaw)
        assertEquals(1, out.single().sysId)
    }

    @Test
    fun `two frames in one chunk parse twice`() {
        val one = vehicle.encode(Heartbeat(type = 2, autopilot = 3, customMode = 4))
        val two = vehicle.encode(Statustext(severity = 6, text = "hello"))
        val frames = MavlinkFrameParser().parse(one + two)
        assertEquals(2, frames.size)
        assertEquals(0, frames[0].msgId)
        assertEquals("hello", (frames[1].decode() as Statustext).text)
        assertEquals((frames[0].seq + 1) and 0xFF, frames[1].seq)
    }

    @Test
    fun `corrupted crc is dropped and counted and the parser resynchronises`() {
        val bad = vehicle.encode(Heartbeat(type = 2))
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x55).toByte()
        val good = vehicle.encode(Heartbeat(type = 13))
        val parser = MavlinkFrameParser()
        val frames = parser.parse(bad + good)
        assertEquals(1, frames.size)
        assertEquals(13, (frames.single().decode() as Heartbeat).type)
        assertEquals(1L, parser.stats.crcErrors)
        assertEquals(1L, parser.stats.frames)
    }

    @Test
    fun `garbage before a frame is discarded`() {
        val parser = MavlinkFrameParser()
        val frames = parser.parse(byteArrayOf(1, 2, 3) + vehicle.encode(Heartbeat()))
        assertEquals(1, frames.size)
        assertEquals(3L, parser.stats.discardedBytes)
    }

    @Test
    fun `mavlink 1 frames parse`() {
        // HEARTBEAT v1: payload is the full 9-byte base payload.
        val payload = Heartbeat(type = 2, autopilot = 3, mavlinkVersion = 3).let {
            it.encodePayload().copyOf(Heartbeat.BASE_LENGTH)
        }
        val frame = ByteArray(6 + payload.size + 2)
        frame[0] = 0xFE.toByte()
        frame[1] = payload.size.toByte()
        frame[2] = 7
        frame[3] = 1
        frame[4] = 1
        frame[5] = 0
        System.arraycopy(payload, 0, frame, 6, payload.size)
        var crc = MavCrc.accumulate(MavCrc.SEED, frame, 1, 5 + payload.size)
        crc = MavCrc.accumulate(crc, Heartbeat.CRC_EXTRA)
        frame[6 + payload.size] = crc.toByte()
        frame[7 + payload.size] = (crc shr 8).toByte()
        val parsed = MavlinkFrameParser().parse(frame).single()
        assertEquals(1, parsed.version)
        assertEquals(7, parsed.seq)
        assertEquals(3, (parsed.decode() as Heartbeat).mavlinkVersion)
    }

    @Test
    fun `signed frames carry a verifiable signature`() {
        val key = ByteArray(32) { it.toByte() }
        val writer = MavlinkFrameWriter(signing = MavlinkSigning(key, linkId = 3) { MavlinkSigning.EPOCH_2015_MS + 1000 })
        val bytes = writer.encode(Heartbeat(type = 6))
        val frame = MavlinkFrameParser().parse(bytes).single()
        val signature = frame.signature
        assertNotNull(signature)
        assertEquals(3, signature!![0].toInt())
        val crcEnd = bytes.size - MavlinkConstants.SIGNATURE_LEN
        val expected = java.security.MessageDigest.getInstance("SHA-256").run {
            update(key)
            update(bytes, 0, crcEnd)
            update(signature, 0, 7)
            digest().copyOf(6)
        }
        assertArrayEquals(expected, signature.copyOfRange(7, 13))
        val second = MavlinkFrameParser().parse(writer.encode(Heartbeat())).single().signature!!
        val ts1 = (1..6).fold(0L) { acc, i -> acc or ((signature[i].toLong() and 0xFF) shl (8 * (i - 1))) }
        val ts2 = (1..6).fold(0L) { acc, i -> acc or ((second[i].toLong() and 0xFF) shl (8 * (i - 1))) }
        assertEquals(ts1 + 1, ts2)
    }

    @Test
    fun `unknown message ids are skipped and counted`() {
        val writer = MavlinkFrameWriter()
        val good = writer.encode(Heartbeat())
        val unknown = good.copyOf()
        unknown[7] = 0x34
        unknown[8] = 0x12
        unknown[9] = 0x7F
        val parser = MavlinkFrameParser()
        val frames = parser.parse(unknown + good)
        assertEquals(1, frames.size)
        assertEquals(1L, parser.stats.unknownMessages)
        assertNull(frames.single().signature)
    }
}
