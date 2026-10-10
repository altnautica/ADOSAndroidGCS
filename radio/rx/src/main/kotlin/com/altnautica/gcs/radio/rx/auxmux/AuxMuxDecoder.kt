package com.altnautica.gcs.radio.rx.auxmux

/**
 * The auxiliary downlink framing: one datagram is one frame,
 *
 * ```
 * 0xAD 0x02 | version 0x01 | channel | u16 BE length | payload
 * ```
 *
 * A frame whose magic or version does not match is dropped, never guessed
 * at. Several MAVLink frames are batched into one [AuxChannel.MAVLINK]
 * payload; [splitMavlinkFrames] separates them.
 */
object AuxMuxDecoder {
    const val HEADER_LEN = 6
    const val MAX_PAYLOAD = 1200
    private const val MAGIC_0 = 0xAD
    private const val MAGIC_1 = 0x02
    private const val VERSION = 0x01

    fun decode(frame: ByteArray): AuxDecodeResult {
        if (frame.size < HEADER_LEN) return AuxDecodeResult.Error(AuxDecodeError.TooShort)
        if (frame[0].u8() != MAGIC_0 || frame[1].u8() != MAGIC_1) return AuxDecodeResult.Error(AuxDecodeError.BadMagic)
        if (frame[2].u8() != VERSION) return AuxDecodeResult.Error(AuxDecodeError.UnsupportedVersion(frame[2].u8()))
        val channel = AuxChannel.fromByte(frame[3].u8())
            ?: return AuxDecodeResult.Error(AuxDecodeError.UnknownChannel(frame[3].u8()))
        val declared = (frame[4].u8() shl 8) or frame[5].u8()
        if (declared > MAX_PAYLOAD) return AuxDecodeResult.Error(AuxDecodeError.TooLong(declared))
        val actual = frame.size - HEADER_LEN
        if (declared != actual) return AuxDecodeResult.Error(AuxDecodeError.LengthMismatch(declared, actual))
        return AuxDecodeResult.Frame(channel, frame.copyOfRange(HEADER_LEN, frame.size))
    }

    /** Encodes one frame; null when the payload is over [MAX_PAYLOAD]. */
    fun encode(channel: AuxChannel, payload: ByteArray): ByteArray? {
        if (payload.size > MAX_PAYLOAD) return null
        val out = ByteArray(HEADER_LEN + payload.size)
        out[0] = MAGIC_0.toByte()
        out[1] = MAGIC_1.toByte()
        out[2] = VERSION.toByte()
        out[3] = channel.wire.toByte()
        out[4] = (payload.size ushr 8).toByte()
        out[5] = payload.size.toByte()
        payload.copyInto(out, HEADER_LEN)
        return out
    }

    /**
     * Byte length of the MAVLink frame starting at [offset], read from the
     * header only (v2: 12 + payload, +13 when signed; v1: 8 + payload), or
     * null when the bytes there do not start a frame.
     */
    fun mavlinkFrameLength(buf: ByteArray, offset: Int = 0): Int? {
        if (offset >= buf.size) return null
        return when (buf[offset].u8()) {
            0xFD -> {
                if (offset + 2 >= buf.size) return null
                val payload = buf[offset + 1].u8()
                val signed = buf[offset + 2].u8() and 0x01 != 0
                12 + payload + if (signed) 13 else 0
            }
            0xFE -> {
                if (offset + 1 >= buf.size) return null
                8 + buf[offset + 1].u8()
            }
            else -> null
        }
    }

    /**
     * Splits back-to-back MAVLink frames. Stops at the first byte that does
     * not begin a complete frame, so a truncated tail is dropped instead of
     * being mis-split.
     */
    fun splitMavlinkFrames(buf: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var at = 0
        while (at < buf.size) {
            val len = mavlinkFrameLength(buf, at) ?: break
            if (len == 0 || at + len > buf.size) break
            out += buf.copyOfRange(at, at + len)
            at += len
        }
        return out
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
}

/** Logical streams on the aux lane. Values are pinned on the wire. */
enum class AuxChannel(val wire: Int) {
    MAVLINK(1),
    STATUS(2),
    IDENTITY(3),
    REQUEST(4),
    RESPONSE(5),
    LINK_FEEDBACK(6),
    CONFIG_TUNNEL(7),
    APP_STREAM(8),
    APP_COMMAND(9),
    ;

    companion object {
        fun fromByte(value: Int): AuxChannel? = entries.firstOrNull { it.wire == value }
    }
}

sealed interface AuxDecodeError {
    data object TooShort : AuxDecodeError
    data object BadMagic : AuxDecodeError
    data class UnsupportedVersion(val version: Int) : AuxDecodeError
    data class UnknownChannel(val channel: Int) : AuxDecodeError
    data class LengthMismatch(val declared: Int, val actual: Int) : AuxDecodeError
    data class TooLong(val declared: Int) : AuxDecodeError
}

sealed interface AuxDecodeResult {
    class Frame(val channel: AuxChannel, val payload: ByteArray) : AuxDecodeResult
    data class Error(val error: AuxDecodeError) : AuxDecodeResult
}
