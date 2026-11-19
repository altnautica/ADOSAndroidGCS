package com.altnautica.gcs.feature.cockpit.pilot

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * MessagePack for `pilot.v1` bodies, matching the onboard stream byte for
 * byte: a JSON client frame becomes the msgpack the pilot stream sends the
 * pilot (object keys in sorted order, integers in their most compact form,
 * every non-integer number as a 64-bit float, strings and containers with the
 * shortest header), and a pilot message decodes back to JSON.
 */
internal object PilotMsgpack {

    fun encode(e: JsonElement): ByteArray = ByteArrayOutputStream().also { write(it, e) }.toByteArray()

    private fun write(out: ByteArrayOutputStream, e: JsonElement) {
        when (e) {
            is JsonNull -> out.write(0xC0)
            is JsonObject -> {
                header(out, e.size, 0x80, 0xDE, 0xDF)
                for (k in e.keys.sorted()) {
                    str(out, k)
                    write(out, e.getValue(k))
                }
            }
            is JsonArray -> {
                header(out, e.size, 0x90, 0xDC, 0xDD)
                e.forEach { write(out, it) }
            }
            is JsonPrimitive -> when {
                e.isString -> str(out, e.content)
                e.content == "true" -> out.write(0xC3)
                e.content == "false" -> out.write(0xC2)
                else -> number(out, e.content)
            }
        }
    }

    /** A number as JSON text: an integer literal that fits 64 bits stays an integer, anything else is a float. */
    private fun number(out: ByteArrayOutputStream, text: String) {
        val isInteger = text.none { it == '.' || it == 'e' || it == 'E' }
        val big = if (isInteger) text.toBigIntegerOrNull() else null
        when {
            big != null && big.signum() >= 0 && big.bitLength() <= 64 -> uint(out, big)
            big != null && big.signum() < 0 && big.bitLength() <= 63 -> sint(out, big.toLong())
            else -> {
                out.write(0xCB)
                be(out, java.lang.Double.doubleToRawLongBits(text.toDouble()), 8)
            }
        }
    }

    private fun uint(out: ByteArrayOutputStream, v: BigInteger) {
        val l = v.toLong()
        when {
            v < BigInteger.valueOf(128) -> out.write(l.toInt())
            v < BigInteger.valueOf(0x100) -> { out.write(0xCC); be(out, l, 1) }
            v < BigInteger.valueOf(0x10000) -> { out.write(0xCD); be(out, l, 2) }
            v < BigInteger.valueOf(0x1_0000_0000) -> { out.write(0xCE); be(out, l, 4) }
            else -> { out.write(0xCF); be(out, l, 8) }
        }
    }

    private fun sint(out: ByteArrayOutputStream, v: Long) {
        when {
            v >= -32 -> out.write(v.toInt() and 0xFF)
            v >= Byte.MIN_VALUE -> { out.write(0xD0); be(out, v, 1) }
            v >= Short.MIN_VALUE -> { out.write(0xD1); be(out, v, 2) }
            v >= Int.MIN_VALUE -> { out.write(0xD2); be(out, v, 4) }
            else -> { out.write(0xD3); be(out, v, 8) }
        }
    }

    private fun str(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        when {
            b.size < 32 -> out.write(0xA0 or b.size)
            b.size < 0x100 -> { out.write(0xD9); be(out, b.size.toLong(), 1) }
            b.size < 0x10000 -> { out.write(0xDA); be(out, b.size.toLong(), 2) }
            else -> { out.write(0xDB); be(out, b.size.toLong(), 4) }
        }
        out.write(b)
    }

    private fun header(out: ByteArrayOutputStream, n: Int, fix: Int, m16: Int, m32: Int) {
        when {
            n < 16 -> out.write(fix or n)
            n < 0x10000 -> { out.write(m16); be(out, n.toLong(), 2) }
            else -> { out.write(m32); be(out, n.toLong(), 4) }
        }
    }

    private fun be(out: ByteArrayOutputStream, v: Long, n: Int) {
        for (i in n - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xFF)
    }

    /** The JSON a msgpack value reads as, or null when [bytes] is not one whole value. */
    fun decode(bytes: ByteArray): JsonElement? = try {
        Reader(bytes).run { value().takeIf { at == bytes.size } }
    } catch (_: IndexOutOfBoundsException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private class Reader(val b: ByteArray) {
        var at = 0

        fun value(): JsonElement {
            val t = u8()
            return when {
                t <= 0x7F -> JsonPrimitive(t)
                t in 0x80..0x8F -> map(t and 0x0F)
                t in 0x90..0x9F -> array(t and 0x0F)
                t in 0xA0..0xBF -> JsonPrimitive(text(t and 0x1F))
                t >= 0xE0 -> JsonPrimitive(t - 0x100)
                else -> when (t) {
                    0xC0 -> JsonNull
                    0xC2 -> JsonPrimitive(false)
                    0xC3 -> JsonPrimitive(true)
                    0xC4 -> bin(n(1))
                    0xC5 -> bin(n(2))
                    0xC6 -> bin(n(4))
                    0xCA -> JsonPrimitive(java.lang.Float.intBitsToFloat(n(4).toInt()).toDouble())
                    0xCB -> JsonPrimitive(java.lang.Double.longBitsToDouble(n(8)))
                    0xCC -> JsonPrimitive(n(1))
                    0xCD -> JsonPrimitive(n(2))
                    0xCE -> JsonPrimitive(n(4))
                    0xCF -> n(8).let { if (it >= 0) JsonPrimitive(it) else JsonPrimitive(BigInteger(java.lang.Long.toUnsignedString(it))) }
                    0xD0 -> JsonPrimitive(b[at++].toLong())
                    0xD1 -> JsonPrimitive(n(2).toShort().toLong())
                    0xD2 -> JsonPrimitive(n(4).toInt().toLong())
                    0xD3 -> JsonPrimitive(n(8))
                    0xD9 -> JsonPrimitive(text(n(1).toInt()))
                    0xDA -> JsonPrimitive(text(n(2).toInt()))
                    0xDB -> JsonPrimitive(text(n(4).toInt()))
                    0xDC -> array(n(2).toInt())
                    0xDD -> array(n(4).toInt())
                    0xDE -> map(n(2).toInt())
                    0xDF -> map(n(4).toInt())
                    else -> throw IllegalArgumentException("msgpack type 0x${t.toString(16)} not read")
                }
            }
        }

        private fun map(count: Int): JsonObject {
            val m = LinkedHashMap<String, JsonElement>()
            repeat(count) {
                val k = value()
                val key = if (k is JsonPrimitive) k.content else k.toString()
                m[key] = value()
            }
            return JsonObject(m)
        }

        private fun array(count: Int): JsonArray = JsonArray(List(count) { value() })

        private fun bin(len: Long): JsonArray = JsonArray(List(len.toInt()) { JsonPrimitive(u8()) })

        private fun text(len: Int): String {
            require(len >= 0 && at + len <= b.size)
            return String(b, at, len, Charsets.UTF_8).also { at += len }
        }

        private fun u8(): Int = b[at++].toInt() and 0xFF

        private fun n(bytes: Int): Long {
            var v = 0L
            repeat(bytes) { v = (v shl 8) or u8().toLong() }
            return v
        }
    }
}
