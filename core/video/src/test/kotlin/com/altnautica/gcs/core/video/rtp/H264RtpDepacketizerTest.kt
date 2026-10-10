package com.altnautica.gcs.core.video.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264RtpDepacketizerTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65, 0x11, 0x22, 0x33, 0x44, 0x55)
    private val pSlice = byteArrayOf(0x41, 0x66, 0x77)

    private fun rtp(seq: Int, ts: Long, marker: Boolean, payload: ByteArray): ByteArray {
        val h = byteArrayOf(
            0x80.toByte(),
            ((if (marker) 0x80 else 0) or 96).toByte(),
            (seq shr 8).toByte(), seq.toByte(),
            (ts shr 24).toByte(), (ts shr 16).toByte(), (ts shr 8).toByte(), ts.toByte(),
            0, 0, 0, 1,
        )
        return h + payload
    }

    private fun annexB(vararg nals: ByteArray) =
        nals.fold(ByteArray(0)) { acc, n -> acc + H264RtpDepacketizer.START_CODE + n }

    private fun stapA(vararg nals: ByteArray): ByteArray =
        nals.fold(byteArrayOf(0x78)) { acc, n -> acc + byteArrayOf((n.size shr 8).toByte(), n.size.toByte()) + n }

    private fun fuA(nal: ByteArray, parts: Int): List<ByteArray> {
        val header = nal[0].toInt() and 0xFF
        val indicator = ((header and 0xE0) or 28).toByte()
        val body = nal.copyOfRange(1, nal.size)
        val chunk = (body.size + parts - 1) / parts
        return body.toList().chunked(chunk).mapIndexed { i, c ->
            var fu = header and 0x1F
            if (i == 0) fu = fu or 0x80
            if (i == parts - 1) fu = fu or 0x40
            byteArrayOf(indicator, fu.toByte()) + c.toByteArray()
        }
    }

    @Test
    fun `single nal idr becomes one key frame`() {
        val d = H264RtpDepacketizer()
        val units = d.push(rtp(1, 9000, true, idr))
        assertEquals(1, units.size)
        assertArrayEquals(annexB(idr), units[0].data)
        assertTrue(units[0].keyFrame)
        assertEquals(9000L, units[0].rtpTimestamp)
    }

    @Test
    fun `stap-a unpacks every aggregated nal`() {
        val d = H264RtpDepacketizer()
        d.push(rtp(1, 100, false, stapA(sps, pps)))
        val units = d.push(rtp(2, 100, true, idr))
        assertEquals(1, units.size)
        assertArrayEquals(annexB(sps, pps, idr), units[0].data)
    }

    @Test
    fun `fu-a fragments reassemble into the original nal`() {
        val d = H264RtpDepacketizer()
        val frags = fuA(idr, 3)
        assertTrue(d.push(rtp(10, 200, false, frags[0])).isEmpty())
        assertTrue(d.push(rtp(11, 200, false, frags[1])).isEmpty())
        val units = d.push(rtp(12, 200, true, frags[2]))
        assertArrayEquals(annexB(idr), units.single().data)
    }

    @Test
    fun `timestamp change closes a unit without a marker`() {
        val d = H264RtpDepacketizer()
        assertTrue(d.push(rtp(1, 100, false, idr)).isEmpty())
        val units = d.push(rtp(2, 200, true, pSlice))
        assertEquals(2, units.size)
        assertTrue(units[0].keyFrame)
        assertFalse(units[1].keyFrame)
    }

    @Test
    fun `p frames before the first idr are dropped`() {
        val d = H264RtpDepacketizer()
        assertTrue(d.push(rtp(1, 100, true, pSlice)).isEmpty())
        assertEquals(1, d.push(rtp(2, 200, true, idr)).size)
    }

    @Test
    fun `loss drops p frames until the next idr`() {
        val d = H264RtpDepacketizer()
        d.push(rtp(1, 100, true, idr))
        assertEquals(1, d.push(rtp(2, 200, true, pSlice)).size)
        // seq 3 lost
        assertTrue(d.push(rtp(4, 400, true, pSlice)).isEmpty())
        assertTrue(d.push(rtp(5, 500, true, pSlice)).isEmpty())
        val resumed = d.push(rtp(6, 600, true, idr))
        assertTrue(resumed.single().keyFrame)
        assertEquals(1, d.push(rtp(7, 700, true, pSlice)).size)
        assertEquals(1L, d.lossEvents)
    }

    @Test
    fun `loss inside a fu-a drops the broken picture`() {
        val d = H264RtpDepacketizer()
        d.push(rtp(1, 100, true, idr))
        val frags = fuA(byteArrayOf(0x41) + ByteArray(30) { it.toByte() }, 3)
        d.push(rtp(2, 200, false, frags[0]))
        // seq 3 (middle fragment) lost
        assertTrue(d.push(rtp(4, 200, true, frags[2])).isEmpty())
        assertTrue(d.push(rtp(5, 300, true, idr)).single().keyFrame)
    }

    @Test
    fun `parameter sets survive a loss and ride with the idr`() {
        val d = H264RtpDepacketizer()
        d.push(rtp(1, 100, true, idr))
        // seq 2 lost
        val units = d.push(rtp(3, 300, true, stapA(sps, pps)))
        assertTrue(units.single().configOnly)
        assertTrue(d.push(rtp(4, 400, true, idr)).single().keyFrame)
    }

    @Test
    fun `sequence wraps without counting loss`() {
        val d = H264RtpDepacketizer()
        d.push(rtp(0xFFFF, 100, true, idr))
        assertEquals(1, d.push(rtp(0, 200, true, pSlice)).size)
        assertEquals(0L, d.lossEvents)
    }
}
