package com.altnautica.gcs.core.mavlink

import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.messages.MavMessages

/** One validated MAVLink frame. [payload] is exactly as received (MAVLink 2 frames may be truncated). */
class MavlinkFrame(
    /** 1 or 2. */
    val version: Int,
    val incompatFlags: Int,
    val compatFlags: Int,
    val seq: Int,
    val sysId: Int,
    val compId: Int,
    val msgId: Int,
    val payload: ByteArray,
    /** 13-byte MAVLink 2 signature block when the frame was signed. */
    val signature: ByteArray?,
) {
    /** Decodes the payload with the dialect codecs; null for an id the dialect lacks. */
    fun decode(): MavMessage? = MavMessages.decode(msgId, payload)

    override fun toString(): String =
        "MavlinkFrame(v$version, seq=$seq, sys=$sysId, comp=$compId, msg=$msgId, len=${payload.size})"
}

/** MAVLink X.25 / CRC-16-MCRF4XX checksum. */
object MavCrc {
    const val SEED: Int = 0xFFFF

    fun accumulate(crc: Int, b: Int): Int {
        var tmp = (b xor (crc and 0xFF)) and 0xFF
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
    }

    fun accumulate(crc: Int, bytes: ByteArray, offset: Int, length: Int): Int {
        var c = crc
        for (i in offset until offset + length) c = accumulate(c, bytes[i].toInt() and 0xFF)
        return c
    }
}

object MavlinkConstants {
    const val STX_V1: Int = 0xFE
    const val STX_V2: Int = 0xFD
    const val HEADER_V1: Int = 6
    const val HEADER_V2: Int = 10
    const val SIGNATURE_LEN: Int = 13
    const val INCOMPAT_SIGNED: Int = 0x01

    /** Ground control station identity used by this app. */
    const val GCS_SYSTEM_ID: Int = 255
    const val GCS_COMPONENT_ID: Int = 190
}
