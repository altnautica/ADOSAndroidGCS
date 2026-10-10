package com.altnautica.gcs.radio.rx.video

import com.altnautica.gcs.core.video.rtp.H264RtpDepacketizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-radio video plane carries RTP/H.264 exactly as the drone's
 * encoder emits it; this drives a recovered datagram sequence (STAP-A
 * parameter sets, an FU-A IDR, a single-NAL P slice) through the
 * depacketizer the video session uses.
 */
class PhoneRadioRtpPathTest {

    private fun rtp(seq: Int, ts: Long, marker: Boolean, payload: ByteArray): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte()
        h[1] = ((if (marker) 0x80 else 0) or 96).toByte()
        h[2] = (seq shr 8).toByte()
        h[3] = seq.toByte()
        for (i in 0 until 4) h[4 + i] = (ts ushr (24 - 8 * i)).toByte()
        return h + payload
    }

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    private val idr = byteArrayOf(0x65) + ByteArray(2000) { (it % 251).toByte() }

    @Test
    fun `parameter sets, fragmented idr and p slice decode into access units`() {
        val d = H264RtpDepacketizer()
        val stapA = byteArrayOf(24) + byteArrayOf(0, sps.size.toByte()) + sps + byteArrayOf(0, pps.size.toByte()) + pps
        val units = ArrayList<com.altnautica.gcs.core.video.rtp.H264AccessUnit>()
        units += d.push(rtp(1, 9000, false, stapA))
        // FU-A: indicator keeps NRI, type 28; header carries start/end and NAL type 5.
        val body = idr.copyOfRange(1, idr.size)
        val chunks = body.toList().chunked(1200).map { it.toByteArray() }
        chunks.forEachIndexed { i, c ->
            val start = if (i == 0) 0x80 else 0
            val end = if (i == chunks.lastIndex) 0x40 else 0
            units += d.push(rtp(2 + i, 9000, i == chunks.lastIndex, byteArrayOf(0x7C, (start or end or 5).toByte()) + c))
        }
        units += d.push(rtp(2 + chunks.size, 12000, true, byteArrayOf(0x41, 0x9A.toByte(), 0x01)))

        assertEquals(2, units.size)
        val key = units[0]
        assertTrue(key.keyFrame)
        val nals = H264RtpDepacketizer.splitAnnexB(key.data)
        assertArrayEquals(sps, nals[0])
        assertArrayEquals(pps, nals[1])
        assertArrayEquals(idr, nals[2])
        assertEquals(9000L, key.rtpTimestamp)
        assertEquals(100_000L, key.rtpTimestamp * 1_000_000L / 90_000L)
        assertTrue(!units[1].keyFrame && !units[1].configOnly)
    }

    @Test
    fun `a lost fragment holds the picture until the next idr`() {
        val d = H264RtpDepacketizer()
        val out = ArrayList<com.altnautica.gcs.core.video.rtp.H264AccessUnit>()
        out += d.push(rtp(1, 3000, true, byteArrayOf(0x65, 1, 2, 3)))
        out += d.push(rtp(3, 6000, true, byteArrayOf(0x41, 4, 5)))
        out += d.push(rtp(4, 9000, true, byteArrayOf(0x41, 6, 7)))
        out += d.push(rtp(5, 12000, true, byteArrayOf(0x65, 8, 9)))
        assertEquals(listOf(3000L, 12000L), out.map { it.rtpTimestamp })
    }
}
