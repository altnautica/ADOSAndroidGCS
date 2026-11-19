package com.altnautica.gcs.radio.rx.aux

/**
 * The config channel's frames: MAVLink v2 TUNNEL (id 385) frames of private
 * payload type `0xADC0`, each carrying one chunk
 * `0xAD 0x03 | version 1 | flags | request_id u32 LE | seq u16 LE | total u16 LE | data`
 * (flags bit 0: response, bit 1: error body). One frame rides one aux frame.
 */
internal object TunnelFrames {
    const val MSG_ID = 385
    const val CRC_EXTRA = 147
    const val PAYLOAD_TYPE = 0xADC0
    const val MAX_PAYLOAD = 128
    const val HEADER_LEN = 12
    const val MAX_CHUNK_DATA = MAX_PAYLOAD - HEADER_LEN
    const val MAX_CHUNKS = 64
    const val MAX_BODY = 8 * 1024
    private const val MAGIC_0 = 0xAD
    private const val MAGIC_1 = 0x03
    private const val VERSION = 1
    private const val FLAG_RESPONSE = 1
    private const val FLAG_ERROR = 2

    class Chunk(val requestId: Int, val seq: Int, val total: Int, val isResponse: Boolean, val isError: Boolean, val data: ByteArray)

    /** The TUNNEL frames of one request body, numbered from [firstSeq]; null past [MAX_CHUNKS]. */
    fun request(requestId: Int, body: ByteArray, firstSeq: Int, sysId: Int = 1, compId: Int = 1): List<ByteArray>? {
        val total = if (body.isEmpty()) 1 else (body.size + MAX_CHUNK_DATA - 1) / MAX_CHUNK_DATA
        if (total > MAX_CHUNKS) return null
        return (0 until total).map { seq ->
            val data = body.copyOfRange(minOf(seq * MAX_CHUNK_DATA, body.size), minOf((seq + 1) * MAX_CHUNK_DATA, body.size))
            val chunk = ByteArray(HEADER_LEN + data.size)
            chunk[0] = MAGIC_0.toByte()
            chunk[1] = MAGIC_1.toByte()
            chunk[2] = VERSION.toByte()
            chunk[3] = 0
            le(chunk, 4, requestId.toLong(), 4)
            le(chunk, 8, seq.toLong(), 2)
            le(chunk, 10, total.toLong(), 2)
            data.copyInto(chunk, HEADER_LEN)
            frame(chunk, (firstSeq + seq) and 0xFF, sysId, compId)
        }
    }

    /** A v2 TUNNEL frame around [payload], trailing zeros truncated, X.25 checksum with the CRC extra. */
    fun frame(payload: ByteArray, sequence: Int, sysId: Int, compId: Int): ByteArray {
        val body = ByteArray(5 + MAX_PAYLOAD)
        le(body, 0, PAYLOAD_TYPE.toLong(), 2)
        body[4] = payload.size.toByte()
        payload.copyInto(body, 5)
        var len = body.size
        while (len > 1 && body[len - 1].toInt() == 0) len--
        val out = ByteArray(10 + len + 2)
        out[0] = 0xFD.toByte()
        out[1] = len.toByte()
        out[4] = sequence.toByte()
        out[5] = sysId.toByte()
        out[6] = compId.toByte()
        le(out, 7, MSG_ID.toLong(), 3)
        body.copyInto(out, 10, 0, len)
        var crc = 0xFFFF
        for (i in 1 until 10 + len) crc = x25(out[i].toInt() and 0xFF, crc)
        crc = x25(CRC_EXTRA, crc)
        out[10 + len] = crc.toByte()
        out[11 + len] = (crc ushr 8).toByte()
        return out
    }

    /** The chunk a TUNNEL frame of this channel carries, or null for anything else. */
    fun parse(frame: ByteArray): Chunk? {
        if (frame.size < 12 || frame[0].toInt() and 0xFF != 0xFD) return null
        val msgId = (frame[7].toInt() and 0xFF) or ((frame[8].toInt() and 0xFF) shl 8) or ((frame[9].toInt() and 0xFF) shl 16)
        if (msgId != MSG_ID) return null
        val bodyLen = frame[1].toInt() and 0xFF
        if (frame.size < 10 + bodyLen + 2 || bodyLen < 2) return null
        val type = (frame[10].toInt() and 0xFF) or ((frame[11].toInt() and 0xFF) shl 8)
        if (type != PAYLOAD_TYPE) return null
        val declared = if (bodyLen > 4) frame[14].toInt() and 0xFF else 0
        val present = if (bodyLen > 5) frame.copyOfRange(15, 10 + bodyLen) else ByteArray(0)
        if (present.size > declared) return null
        val p = present.copyOf(declared)
        if (p.size < HEADER_LEN || p[0].toInt() and 0xFF != MAGIC_0 || p[1].toInt() and 0xFF != MAGIC_1 || p[2].toInt() != VERSION) {
            return null
        }
        val seq = u16le(p, 8)
        val total = u16le(p, 10)
        if (total == 0 || seq >= total) return null
        val flags = p[3].toInt()
        val id = u16le(p, 4) or (u16le(p, 6) shl 16)
        return Chunk(id, seq, total, flags and FLAG_RESPONSE != 0, flags and FLAG_ERROR != 0, p.copyOfRange(HEADER_LEN, p.size))
    }

    private fun x25(b: Int, crc: Int): Int {
        var tmp = b xor (crc and 0xFF)
        tmp = (tmp xor (tmp shl 4)) and 0xFF
        return ((crc ushr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp ushr 4)) and 0xFFFF
    }

    private fun le(b: ByteArray, at: Int, v: Long, n: Int) {
        for (i in 0 until n) b[at + i] = (v ushr (8 * i)).toByte()
    }

    private fun u16le(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}
