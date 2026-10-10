package com.altnautica.gcs.feature.logs.tlog

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/** One record of a telemetry log: wall-clock receive time and the raw MAVLink frame bytes. */
class TlogRecord(val timeUs: Long, val frame: ByteArray)

/**
 * Reads `.tlog` files: records of an 8-byte timestamp (microseconds since the Unix epoch)
 * followed by one raw MAVLink 1 or 2 frame. The common layout stores the timestamp
 * big-endian; files whose first timestamp only makes sense little-endian are read that way.
 */
class TlogReader(input: InputStream) : Iterator<TlogRecord>, AutoCloseable {
    private val data = DataInputStream(input.buffered())
    private var littleEndian: Boolean? = null
    private var next: TlogRecord? = null
    private var done = false

    override fun hasNext(): Boolean {
        if (next == null && !done) next = readRecord().also { if (it == null) done = true }
        return next != null
    }

    override fun next(): TlogRecord {
        if (!hasNext()) throw NoSuchElementException()
        return next!!.also { next = null }
    }

    override fun close() = data.close()

    private fun readRecord(): TlogRecord? = try {
        val raw = data.readLong()
        val le = littleEndian ?: (!plausible(raw) && plausible(java.lang.Long.reverseBytes(raw))).also { littleEndian = it }
        val timeUs = if (le) java.lang.Long.reverseBytes(raw) else raw
        val stx = data.readUnsignedByte()
        val len = data.readUnsignedByte()
        val frame = when (stx) {
            STX_V1 -> ByteArray(V1_OVERHEAD + len).also {
                it[0] = stx.toByte(); it[1] = len.toByte()
                data.readFully(it, 2, it.size - 2)
            }
            STX_V2 -> {
                val incompat = data.readUnsignedByte()
                val signed = incompat and INCOMPAT_SIGNED != 0
                ByteArray(V2_OVERHEAD + len + if (signed) SIGNATURE else 0).also {
                    it[0] = stx.toByte(); it[1] = len.toByte(); it[2] = incompat.toByte()
                    data.readFully(it, 3, it.size - 3)
                }
            }
            else -> throw TlogFormatException("Unexpected byte 0x%02x where a frame should start".format(stx))
        }
        TlogRecord(timeUs, frame)
    } catch (_: EOFException) {
        // A recording cut off mid-record ends at the last whole record.
        null
    }

    private fun plausible(us: Long) = us in MIN_US..MAX_US

    private companion object {
        const val STX_V1 = 0xFE
        const val STX_V2 = 0xFD
        const val INCOMPAT_SIGNED = 0x01
        const val V1_OVERHEAD = 8 // header 6 + crc 2
        const val V2_OVERHEAD = 12 // header 10 + crc 2
        const val SIGNATURE = 13
        const val MIN_US = 946_684_800_000_000L // 2000-01-01
        const val MAX_US = 4_102_444_800_000_000L // 2100-01-01
    }
}

class TlogFormatException(message: String) : Exception(message)
