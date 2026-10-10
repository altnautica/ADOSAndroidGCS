package com.altnautica.gcs.radio.rx.usb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Reads frame records from the radio service pipe:
 *
 * ```
 * u32 le length | u8 antennaCount | i8 rssiDbm x n | i8 snrDb x n | 802.11 frame
 * ```
 *
 * Each [next] leaves one record body (everything after the length) at the
 * start of [buffer], a direct buffer the native receiver reads in place.
 */
class FrameRecordReader(private val input: InputStream) {

    private val header = ByteArray(4)
    private val scratch = ByteArray(MAX_RECORD)
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(MAX_RECORD)

    /** Length of the record now in [buffer], or -1 at end of stream. */
    fun next(): Int {
        if (!readFully(header, 4, allowEof = true)) return -1
        val len = (header[0].toInt() and 0xFF) or
            ((header[1].toInt() and 0xFF) shl 8) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 24)
        if (len !in 1..MAX_RECORD) throw IOException("frame record length $len out of range")
        readFully(scratch, len, allowEof = false)
        buffer.clear()
        buffer.put(scratch, 0, len)
        buffer.flip()
        return len
    }

    /** The record body now in [buffer], as a heap copy (tests and diagnostics). */
    fun copy(length: Int): ByteArray = scratch.copyOf(length)

    private fun readFully(into: ByteArray, count: Int, allowEof: Boolean): Boolean {
        var off = 0
        while (off < count) {
            val n = input.read(into, off, count - off)
            if (n < 0) {
                if (off == 0 && allowEof) return false
                throw EOFException("frame pipe closed mid-record")
            }
            off += n
        }
        return true
    }

    companion object {
        /** A record never exceeds one atomic pipe write. */
        const val MAX_RECORD = 4096
    }
}

/** Views of one record body (as [FrameRecordReader] leaves it). */
object FrameRecord {
    private const val HEADER_LEN = 24

    fun antennas(body: ByteBuffer): Int = body.get(0).toInt() and 0xFF

    /** Strongest per-antenna RSSI in dBm, or null when the record carries none. */
    fun bestRssi(body: ByteBuffer): Int? {
        val n = antennas(body)
        if (n == 0) return null
        var best = Int.MIN_VALUE
        for (i in 0 until n) best = maxOf(best, body.get(1 + i).toInt())
        return best
    }

    /** Best per-antenna SNR in dB, or null when the record carries none. */
    fun bestSnr(body: ByteBuffer): Int? {
        val n = antennas(body)
        if (n == 0) return null
        var best = Int.MIN_VALUE
        for (i in 0 until n) best = maxOf(best, body.get(1 + n + i).toInt())
        return best
    }

    /**
     * The WFB link id the frame was sent under (`channel_id >> 8`), or null
     * when the frame is not a WFB data-plane frame.
     */
    fun linkId(body: ByteBuffer, length: Int): Int? {
        val n = antennas(body)
        val frame = 1 + 2 * n
        if (length - frame <= HEADER_LEN) return null
        if (body.get(frame + 10) != 0x57.toByte() || body.get(frame + 11) != 0x42.toByte()) return null
        val channelId = ((body.get(frame + 12).toLong() and 0xFF) shl 24) or
            ((body.get(frame + 13).toLong() and 0xFF) shl 16) or
            ((body.get(frame + 14).toLong() and 0xFF) shl 8) or
            (body.get(frame + 15).toLong() and 0xFF)
        return (channelId ushr 8).toInt()
    }
}
