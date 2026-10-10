package com.altnautica.gcs.radio.rx.usb

import com.altnautica.gcs.radio.rx.link.LinkParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException

class FrameRecordReaderTest {

    /** A data-plane 802.11 frame: 24-byte header with `W B` + channel id in the transmitter address. */
    private fun wfbFrame(channelId: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(24)
        header[0] = 0x08
        header[1] = 0x01
        for (i in 4 until 10) header[i] = 0xFF.toByte()
        header[10] = 0x57
        header[11] = 0x42
        for (i in 0 until 4) header[12 + i] = (channelId ushr (24 - 8 * i)).toByte()
        return header + payload
    }

    private fun record(rssi: List<Int>, snr: List<Int>, frame: ByteArray): ByteArray {
        val body = byteArrayOf(rssi.size.toByte()) + rssi.map { it.toByte() } + snr.map { it.toByte() } + frame
        val len = body.size
        return byteArrayOf(len.toByte(), (len shr 8).toByte(), (len shr 16).toByte(), (len shr 24).toByte()) + body
    }

    @Test
    fun `reads consecutive records and exposes link, rssi and snr`() {
        val link = LinkParams.linkId(7, 2)
        val first = record(listOf(-52, -60), listOf(24, 18), wfbFrame(LinkParams.channelId(link, 0), byteArrayOf(1, 2, 3)))
        val second = record(listOf(-70), listOf(9), ByteArray(30))
        val reader = FrameRecordReader(ByteArrayInputStream(first + second))

        val len1 = reader.next()
        assertEquals(first.size - 4, len1)
        assertEquals(2, FrameRecord.antennas(reader.buffer))
        assertEquals(-52, FrameRecord.bestRssi(reader.buffer))
        assertEquals(24, FrameRecord.bestSnr(reader.buffer))
        assertEquals(link, FrameRecord.linkId(reader.buffer, len1))
        assertArrayEquals(first.copyOfRange(4, first.size), reader.copy(len1))

        val len2 = reader.next()
        assertEquals(-70, FrameRecord.bestRssi(reader.buffer))
        assertNull(FrameRecord.linkId(reader.buffer, len2))
        assertEquals(-1, reader.next())
    }

    @Test
    fun `a split read is reassembled`() {
        val rec = record(listOf(-40), listOf(30), wfbFrame(0x0701_00, ByteArray(100) { it.toByte() }))
        val trickle = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int = if (i < rec.size) rec[i++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i >= rec.size) return -1
                b[off] = rec[i++]
                return 1
            }
        }
        val reader = FrameRecordReader(trickle)
        assertEquals(rec.size - 4, reader.next())
        assertEquals(0x0701, FrameRecord.linkId(reader.buffer, rec.size - 4))
    }

    @Test(expected = EOFException::class)
    fun `a record cut mid-way fails loudly`() {
        val rec = record(listOf(-40), listOf(30), ByteArray(40))
        FrameRecordReader(ByteArrayInputStream(rec.copyOf(rec.size - 5))).next()
    }

    @Test(expected = IOException::class)
    fun `an impossible length is rejected`() {
        FrameRecordReader(ByteArrayInputStream(byteArrayOf(0, 0x20, 0, 0))).next()
    }
}
