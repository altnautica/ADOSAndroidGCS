package com.altnautica.gcs.core.mavlink

import com.altnautica.gcs.core.mavlink.messages.MavMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire layout of one field, in the order it sits on the wire. */
data class MavFieldMeta(
    val name: String,
    /** MAVLink primitive name, e.g. `uint16_t` or `char`. */
    val type: String,
    val offset: Int,
    /** 0 for a scalar. */
    val arrayLength: Int,
    val extension: Boolean,
)

/** Static description and decoder of one message type; each generated companion implements it. */
interface MavMessageCodec<T : MavMessage> {
    val id: Int
    val messageName: String
    val crcExtra: Int
    val length: Int
    val baseLength: Int
    val fields: List<MavFieldMeta>
    fun decode(payload: ByteArray): T
}

/** Little-endian payload helpers used by the generated codecs. */
object MavPayload {

    /** Buffer over [payload], zero-filled up to [length] when the sender truncated it. */
    fun reader(payload: ByteArray, length: Int): ByteBuffer {
        val bytes = if (payload.size >= length) payload else payload.copyOf(length)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    fun writer(length: Int): ByteBuffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)

    /** MAVLink 2 truncation: drop trailing zero bytes, keeping at least one byte. */
    fun truncate(b: ByteBuffer): ByteArray {
        val bytes = b.array()
        var end = bytes.size
        while (end > 1 && bytes[end - 1].toInt() == 0) end--
        return if (end == bytes.size) bytes else bytes.copyOf(end)
    }

    fun getString(b: ByteBuffer, offset: Int, n: Int): String {
        var len = 0
        while (len < n && b.get(offset + len).toInt() != 0) len++
        val out = ByteArray(len)
        for (i in 0 until len) out[i] = b.get(offset + i)
        return String(out, Charsets.ISO_8859_1)
    }

    fun putString(b: ByteBuffer, offset: Int, n: Int, value: String) {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until minOf(n, bytes.size)) b.put(offset + i, bytes[i])
    }

    fun getBytes(b: ByteBuffer, offset: Int, n: Int): ByteArray = ByteArray(n) { b.get(offset + it) }

    fun putBytes(b: ByteBuffer, offset: Int, n: Int, v: ByteArray) {
        for (i in 0 until minOf(n, v.size)) b.put(offset + i, v[i])
    }

    fun getUShorts(b: ByteBuffer, offset: Int, n: Int): IntArray =
        IntArray(n) { b.getShort(offset + it * 2).toInt() and 0xFFFF }

    fun getShorts(b: ByteBuffer, offset: Int, n: Int): IntArray = IntArray(n) { b.getShort(offset + it * 2).toInt() }

    fun putShorts(b: ByteBuffer, offset: Int, n: Int, v: IntArray) {
        for (i in 0 until minOf(n, v.size)) b.putShort(offset + i * 2, v[i].toShort())
    }

    fun getInts(b: ByteBuffer, offset: Int, n: Int): IntArray = IntArray(n) { b.getInt(offset + it * 4) }

    fun putInts(b: ByteBuffer, offset: Int, n: Int, v: IntArray) {
        for (i in 0 until minOf(n, v.size)) b.putInt(offset + i * 4, v[i])
    }

    fun getUInts(b: ByteBuffer, offset: Int, n: Int): LongArray =
        LongArray(n) { b.getInt(offset + it * 4).toLong() and 0xFFFFFFFFL }

    fun putUInts(b: ByteBuffer, offset: Int, n: Int, v: LongArray) {
        for (i in 0 until minOf(n, v.size)) b.putInt(offset + i * 4, v[i].toInt())
    }

    fun getLongs(b: ByteBuffer, offset: Int, n: Int): LongArray = LongArray(n) { b.getLong(offset + it * 8) }

    fun putLongs(b: ByteBuffer, offset: Int, n: Int, v: LongArray) {
        for (i in 0 until minOf(n, v.size)) b.putLong(offset + i * 8, v[i])
    }

    fun getFloats(b: ByteBuffer, offset: Int, n: Int): FloatArray = FloatArray(n) { b.getFloat(offset + it * 4) }

    fun putFloats(b: ByteBuffer, offset: Int, n: Int, v: FloatArray) {
        for (i in 0 until minOf(n, v.size)) b.putFloat(offset + i * 4, v[i])
    }

    fun getDoubles(b: ByteBuffer, offset: Int, n: Int): DoubleArray = DoubleArray(n) { b.getDouble(offset + it * 8) }

    fun putDoubles(b: ByteBuffer, offset: Int, n: Int, v: DoubleArray) {
        for (i in 0 until minOf(n, v.size)) b.putDouble(offset + i * 8, v[i])
    }
}
