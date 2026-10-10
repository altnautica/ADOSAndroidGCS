package com.altnautica.gcs.core.mavlink

import com.altnautica.gcs.core.mavlink.MavlinkConstants.HEADER_V1
import com.altnautica.gcs.core.mavlink.MavlinkConstants.HEADER_V2
import com.altnautica.gcs.core.mavlink.MavlinkConstants.INCOMPAT_SIGNED
import com.altnautica.gcs.core.mavlink.MavlinkConstants.SIGNATURE_LEN
import com.altnautica.gcs.core.mavlink.MavlinkConstants.STX_V1
import com.altnautica.gcs.core.mavlink.MavlinkConstants.STX_V2
import com.altnautica.gcs.core.mavlink.messages.MavCrcExtra

/** Counters since the parser was created. */
data class MavlinkParserStats(
    val frames: Long,
    /** Frames whose checksum did not match (resynchronised one byte later). */
    val crcErrors: Long,
    /** Frames skipped whole because the dialect lacks the id, so the checksum cannot be verified. */
    val unknownMessages: Long,
    /** Bytes thrown away while hunting for a start marker. */
    val discardedBytes: Long,
)

/**
 * Stateful MAVLink v1/v2 byte accumulator for one transport.
 *
 * Bytes arrive in arbitrary chunks: a frame may be split across several
 * chunks and one chunk may hold several frames. Bytes are kept until a full
 * frame is present; a frame with a bad checksum is counted and the parser
 * resynchronises on the next start marker. Not thread-safe: feed it from one
 * reader.
 */
class MavlinkFrameParser {

    private var buf = ByteArray(4096)
    private var start = 0
    private var end = 0

    private var frames = 0L
    private var crcErrors = 0L
    private var unknown = 0L
    private var discarded = 0L

    val stats: MavlinkParserStats get() = MavlinkParserStats(frames, crcErrors, unknown, discarded)

    /** Appends [chunk] and calls [onFrame] for every complete, valid frame, in order. */
    fun feed(chunk: ByteArray, offset: Int = 0, length: Int = chunk.size - offset, onFrame: (MavlinkFrame) -> Unit) {
        append(chunk, offset, length)
        while (true) {
            if (!skipToStx()) return
            val avail = end - start
            val stx = buf[start].toInt() and 0xFF
            val headerLen = if (stx == STX_V2) HEADER_V2 else HEADER_V1
            if (avail < headerLen) return
            val payloadLen = buf[start + 1].toInt() and 0xFF
            val incompat = if (stx == STX_V2) buf[start + 2].toInt() and 0xFF else 0
            val signed = stx == STX_V2 && (incompat and INCOMPAT_SIGNED) != 0
            val total = headerLen + payloadLen + 2 + if (signed) SIGNATURE_LEN else 0
            if (avail < total) return

            val msgId = if (stx == STX_V2) {
                (buf[start + 7].toInt() and 0xFF) or
                    ((buf[start + 8].toInt() and 0xFF) shl 8) or
                    ((buf[start + 9].toInt() and 0xFF) shl 16)
            } else {
                buf[start + 5].toInt() and 0xFF
            }
            val crcExtra = MavCrcExtra.crcExtra(msgId)
            if (crcExtra < 0) {
                unknown++
                start += total
                continue
            }
            var crc = MavCrc.accumulate(MavCrc.SEED, buf, start + 1, headerLen - 1 + payloadLen)
            crc = MavCrc.accumulate(crc, crcExtra)
            val crcAt = start + headerLen + payloadLen
            val wireCrc = (buf[crcAt].toInt() and 0xFF) or ((buf[crcAt + 1].toInt() and 0xFF) shl 8)
            if (crc != wireCrc) {
                crcErrors++
                start += 1
                continue
            }

            val payload = buf.copyOfRange(start + headerLen, start + headerLen + payloadLen)
            val frame = if (stx == STX_V2) {
                MavlinkFrame(
                    version = 2,
                    incompatFlags = incompat,
                    compatFlags = buf[start + 3].toInt() and 0xFF,
                    seq = buf[start + 4].toInt() and 0xFF,
                    sysId = buf[start + 5].toInt() and 0xFF,
                    compId = buf[start + 6].toInt() and 0xFF,
                    msgId = msgId,
                    payload = payload,
                    signature = if (signed) buf.copyOfRange(crcAt + 2, crcAt + 2 + SIGNATURE_LEN) else null,
                )
            } else {
                MavlinkFrame(
                    version = 1,
                    incompatFlags = 0,
                    compatFlags = 0,
                    seq = buf[start + 2].toInt() and 0xFF,
                    sysId = buf[start + 3].toInt() and 0xFF,
                    compId = buf[start + 4].toInt() and 0xFF,
                    msgId = msgId,
                    payload = payload,
                    signature = null,
                )
            }
            start += total
            frames++
            onFrame(frame)
        }
    }

    /** Collecting variant of [feed]. */
    fun parse(chunk: ByteArray): List<MavlinkFrame> {
        val out = ArrayList<MavlinkFrame>(2)
        feed(chunk) { out += it }
        return out
    }

    /** Drops buffered bytes, e.g. after the transport reconnects. */
    fun reset() {
        start = 0
        end = 0
    }

    private fun skipToStx(): Boolean {
        while (start < end) {
            val b = buf[start].toInt() and 0xFF
            if (b == STX_V2 || b == STX_V1) return true
            start++
            discarded++
        }
        start = 0
        end = 0
        return false
    }

    private fun append(chunk: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        if (start > 0 && end + length > buf.size) {
            System.arraycopy(buf, start, buf, 0, end - start)
            end -= start
            start = 0
        }
        if (end + length > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, end + length))
        }
        System.arraycopy(chunk, offset, buf, end, length)
        end += length
    }
}
