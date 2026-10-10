package com.altnautica.gcs.radio.rx.auxmux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors mirror the agent's aux-lane framing tests. */
class AuxMuxDecoderTest {

    private fun frame(result: AuxDecodeResult): AuxDecodeResult.Frame = result as AuxDecodeResult.Frame
    private fun error(result: AuxDecodeResult): AuxDecodeError = (result as AuxDecodeResult.Error).error

    @Test
    fun `round trips every channel`() {
        val body = "payload bytes".toByteArray()
        for (ch in listOf(AuxChannel.MAVLINK, AuxChannel.STATUS, AuxChannel.IDENTITY, AuxChannel.REQUEST, AuxChannel.RESPONSE)) {
            val framed = AuxMuxDecoder.encode(ch, body)!!
            assertEquals(AuxMuxDecoder.HEADER_LEN + body.size, framed.size)
            val decoded = frame(AuxMuxDecoder.decode(framed))
            assertEquals(ch, decoded.channel)
            assertArrayEquals(body, decoded.payload)
        }
    }

    @Test
    fun `exact wire bytes`() {
        val framed = AuxMuxDecoder.encode(AuxChannel.MAVLINK, byteArrayOf(0x11, 0x22, 0x33))!!
        assertArrayEquals(byteArrayOf(0xAD.toByte(), 0x02, 0x01, 0x01, 0x00, 0x03, 0x11, 0x22, 0x33), framed)
    }

    @Test
    fun `round trips an empty payload`() {
        val decoded = frame(AuxMuxDecoder.decode(AuxMuxDecoder.encode(AuxChannel.STATUS, ByteArray(0))!!))
        assertEquals(AuxChannel.STATUS, decoded.channel)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `refuses to truncate an oversized payload`() {
        assertNull(AuxMuxDecoder.encode(AuxChannel.MAVLINK, ByteArray(AuxMuxDecoder.MAX_PAYLOAD + 1)))
        assertTrue(AuxMuxDecoder.encode(AuxChannel.MAVLINK, ByteArray(AuxMuxDecoder.MAX_PAYLOAD)) != null)
    }

    @Test
    fun `drops foreign traffic rather than guessing`() {
        assertEquals(AuxDecodeError.BadMagic, error(AuxMuxDecoder.decode("hello!".toByteArray())))
        assertEquals(AuxDecodeError.TooShort, error(AuxMuxDecoder.decode(byteArrayOf(0xAD.toByte()))))
    }

    @Test
    fun `rejects an unsupported version and unknown channel`() {
        val v = AuxMuxDecoder.encode(AuxChannel.MAVLINK, "x".toByteArray())!!
        v[2] = 0x99.toByte()
        assertEquals(AuxDecodeError.UnsupportedVersion(0x99), error(AuxMuxDecoder.decode(v)))
        val c = AuxMuxDecoder.encode(AuxChannel.MAVLINK, "x".toByteArray())!!
        c[3] = 0x7F
        assertEquals(AuxDecodeError.UnknownChannel(0x7F), error(AuxMuxDecoder.decode(c)))
    }

    @Test
    fun `catches a truncated frame of ours`() {
        val f = AuxMuxDecoder.encode(AuxChannel.MAVLINK, "twelve bytes".toByteArray())!!
        assertEquals(AuxDecodeError.LengthMismatch(12, 9), error(AuxMuxDecoder.decode(f.copyOf(f.size - 3))))
    }

    @Test
    fun `rejects a declared length over the maximum`() {
        val f = byteArrayOf(0xAD.toByte(), 0x02, 0x01, 0x01, 0x04, 0xB1.toByte())
        assertEquals(AuxDecodeError.TooLong(1201), error(AuxMuxDecoder.decode(f)))
    }

    @Test
    fun `channel numbers are pinned`() {
        assertEquals(1, AuxChannel.MAVLINK.wire)
        assertEquals(2, AuxChannel.STATUS.wire)
        assertEquals(3, AuxChannel.IDENTITY.wire)
        assertEquals(4, AuxChannel.REQUEST.wire)
        assertEquals(5, AuxChannel.RESPONSE.wire)
        assertEquals(6, AuxChannel.LINK_FEEDBACK.wire)
        assertEquals(7, AuxChannel.CONFIG_TUNNEL.wire)
        assertEquals(AuxChannel.APP_STREAM, AuxChannel.fromByte(8))
        assertEquals(AuxChannel.APP_COMMAND, AuxChannel.fromByte(9))
        assertNull(AuxChannel.fromByte(0))
        assertNull(AuxChannel.fromByte(10))
    }

    private fun v2(payloadLen: Int, signed: Boolean): ByteArray {
        val head = byteArrayOf(0xFD.toByte(), payloadLen.toByte(), if (signed) 0x01 else 0x00, 0, 0, 1, 1, 0, 0, 0)
        return head + ByteArray(payloadLen) { 0xAB.toByte() } + byteArrayOf(0, 0) + (if (signed) ByteArray(13) else ByteArray(0))
    }

    private fun v1(payloadLen: Int): ByteArray =
        byteArrayOf(0xFE.toByte(), payloadLen.toByte(), 0, 1, 1, 0) + ByteArray(payloadLen) { 0xCD.toByte() } + byteArrayOf(0, 0)

    @Test
    fun `frame length reads v1, v2 and the signature block`() {
        assertEquals(22, AuxMuxDecoder.mavlinkFrameLength(v2(10, false)))
        assertEquals(35, AuxMuxDecoder.mavlinkFrameLength(v2(10, true)))
        assertEquals(18, AuxMuxDecoder.mavlinkFrameLength(v1(10)))
        assertNull(AuxMuxDecoder.mavlinkFrameLength(byteArrayOf(0x00) + "junk".toByteArray()))
        assertNull(AuxMuxDecoder.mavlinkFrameLength(ByteArray(0)))
    }

    @Test
    fun `splits a batch back into the frames that went in`() {
        val frames = listOf(v2(5, false), v1(9), v2(0, false), v2(3, true))
        val out = AuxMuxDecoder.splitMavlinkFrames(frames.reduce { a, b -> a + b })
        assertEquals(frames.size, out.size)
        frames.zip(out).forEach { (want, got) -> assertArrayEquals(want, got) }
    }

    @Test
    fun `a single frame batch is unchanged`() {
        val f = v2(12, false)
        val out = AuxMuxDecoder.splitMavlinkFrames(f)
        assertEquals(1, out.size)
        assertArrayEquals(f, out[0])
    }

    @Test
    fun `drops a truncated tail and keeps the whole frames`() {
        val good = v2(8, false)
        val out = AuxMuxDecoder.splitMavlinkFrames(good + v2(20, false).copyOf(6))
        assertEquals(1, out.size)
        assertArrayEquals(good, out[0])
    }

    @Test
    fun `stops at an unrecognisable byte rather than scanning on`() {
        assertEquals(1, AuxMuxDecoder.splitMavlinkFrames(v2(4, false) + byteArrayOf(0x00) + v1(4)).size)
    }

    @Test
    fun `an empty buffer yields no frames`() {
        assertTrue(AuxMuxDecoder.splitMavlinkFrames(ByteArray(0)).isEmpty())
    }
}
