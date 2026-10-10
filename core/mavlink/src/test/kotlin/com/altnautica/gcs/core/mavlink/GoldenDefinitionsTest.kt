package com.altnautica.gcs.core.mavlink

import com.altnautica.gcs.core.mavlink.messages.MavCrcExtra
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.messages.MavMessages
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Compares the generated codecs with the shared wire fixture for the pinned
 * MAVLink revision: ids, CRC_EXTRA, payload lengths and every field offset,
 * then proves each codec reads and writes the bytes at those offsets.
 */
class GoldenDefinitionsTest {

    private val fixture: JsonObject by lazy {
        val text = javaClass.classLoader!!.getResource("mavlink-definitions.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    private val messages get() = fixture.getValue("messages").jsonObject

    @Test
    fun `dialect defines exactly the fixture messages`() {
        val expected = messages.keys.map { it.toInt() }.sorted()
        assertEquals(expected, MavMessages.ids.toList())
    }

    @Test
    fun `every message matches id, crc extra, lengths and field layout`() {
        for ((idText, value) in messages) {
            val id = idText.toInt()
            val m = value.jsonObject
            val codec = MavMessages.codec(id)
            assertNotNull("codec $id", codec)
            codec!!
            val name = m.getValue("name").jsonPrimitive.content
            assertEquals("name $id", name, codec.messageName)
            assertEquals("crcExtra $name", m.getValue("crcExtra").jsonPrimitive.int, codec.crcExtra)
            assertEquals("crcExtra table $name", codec.crcExtra, MavCrcExtra.crcExtra(id))
            assertEquals("length $name", m.getValue("length").jsonPrimitive.int, codec.length)
            assertEquals("length table $name", codec.length, MavCrcExtra.length(id))
            assertEquals("baseLength $name", m.getValue("baseLength").jsonPrimitive.int, codec.baseLength)
            val expectedFields = m.getValue("fields").jsonArray.map { f ->
                val o = f.jsonObject
                MavFieldMeta(
                    name = o.getValue("name").jsonPrimitive.content,
                    type = o.getValue("type").jsonPrimitive.content,
                    offset = o.getValue("offset").jsonPrimitive.int,
                    arrayLength = o.getValue("arrayLength").jsonPrimitive.int,
                    extension = o.getValue("extension").jsonPrimitive.boolean,
                )
            }
            assertEquals("fields $name", expectedFields.sortedBy { it.offset }, codec.fields.sortedBy { it.offset })
        }
    }

    @Test
    fun `fixture enum values match generated constants`() {
        val enums = fixture.getValue("enums").jsonObject
        for ((enumName, entries) in enums) {
            val cls = Class.forName("com.altnautica.gcs.core.mavlink.enums." + className(enumName))
            for ((entry, value) in entries.jsonObject) {
                val actual = cls.getField(entry).get(null) as Number
                assertEquals("$enumName.$entry", value.jsonPrimitive.long, actual.toLong())
            }
        }
    }

    @Test
    fun `every codec reads and writes each field at its fixture offset`() {
        for (id in MavMessages.ids) {
            val codec = MavMessages.codec(id)!!
            val payload = syntheticPayload(codec.fields, codec.length)
            val message = codec.decode(payload)
            for (field in codec.fields) {
                val expected = expectedValue(field, payload)
                val actual = getter(message, field)
                assertValueEquals("${codec.messageName}.${field.name}", expected, actual)
            }
            assertArrayEquals("${codec.messageName} round trip", trimmed(payload), message.encodePayload())
            assertEquals("${codec.messageName} equality", message, codec.decode(message.encodePayload()))
        }
    }

    @Test
    fun `truncated payload decodes zero-filled`() {
        val hb = com.altnautica.gcs.core.mavlink.messages.Heartbeat(customMode = 5, type = 2, autopilot = 3)
        val bytes = hb.encodePayload()
        assertEquals(6, bytes.size) // trailing zeros after autopilot are truncated
        assertEquals(hb, com.altnautica.gcs.core.mavlink.messages.Heartbeat.decode(bytes))
    }

    private fun syntheticPayload(fields: List<MavFieldMeta>, length: Int): ByteArray {
        val b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var seed = 1
        for (f in fields) {
            val count = maxOf(f.arrayLength, 1)
            for (i in 0 until count) {
                seed++
                val at = f.offset + i * size(f.type)
                when (f.type) {
                    "char" -> b.put(at, ('A'.code + (seed % 26)).toByte())
                    "uint8_t", "int8_t" -> b.put(at, (seed * 7 + 1).toByte())
                    "uint16_t", "int16_t" -> b.putShort(at, (seed * 4099 + 3).toShort())
                    "uint32_t", "int32_t" -> b.putInt(at, seed * 1_000_003 + 0x40000000 * (seed % 2))
                    "uint64_t", "int64_t" -> b.putLong(at, seed * 1_000_000_007L shl 7)
                    "float" -> b.putFloat(at, seed * 1.25f - 3f)
                    "double" -> b.putDouble(at, seed * 2.5 - 7.0)
                    else -> error("type ${f.type}")
                }
            }
        }
        return b.array()
    }

    private fun size(type: String) = when (type) {
        "char", "uint8_t", "int8_t" -> 1
        "uint16_t", "int16_t" -> 2
        "uint32_t", "int32_t", "float" -> 4
        else -> 8
    }

    private fun expectedValue(f: MavFieldMeta, payload: ByteArray): Any {
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        fun scalar(at: Int): Any = when (f.type) {
            "uint8_t", "char" -> b.get(at).toInt() and 0xFF
            "int8_t" -> b.get(at).toInt()
            "uint16_t" -> b.getShort(at).toInt() and 0xFFFF
            "int16_t" -> b.getShort(at).toInt()
            "uint32_t" -> b.getInt(at).toLong() and 0xFFFFFFFFL
            "int32_t" -> b.getInt(at)
            "uint64_t", "int64_t" -> b.getLong(at)
            "float" -> b.getFloat(at)
            "double" -> b.getDouble(at)
            else -> error(f.type)
        }
        if (f.arrayLength == 0) return scalar(f.offset)
        if (f.type == "char") return String(payload, f.offset, f.arrayLength, Charsets.ISO_8859_1)
        if (f.type == "uint8_t" || f.type == "int8_t") return payload.copyOfRange(f.offset, f.offset + f.arrayLength).toList()
        return List(f.arrayLength) { scalar(f.offset + it * size(f.type)) }
    }

    private fun getter(message: MavMessage, f: MavFieldMeta): Any {
        val prop = propertyName(f.name)
        val method = if (prop.startsWith("is") && prop.length > 2 && prop[2].isUpperCase()) {
            prop
        } else {
            "get" + prop.replaceFirstChar { it.uppercase() }
        }
        val raw = message.javaClass.getMethod(method).invoke(message)
        return when (raw) {
            is ByteArray -> raw.toList()
            is IntArray -> raw.toList()
            is LongArray -> raw.toList()
            is FloatArray -> raw.toList()
            is DoubleArray -> raw.toList()
            else -> raw
        }
    }

    private fun assertValueEquals(label: String, expected: Any, actual: Any) {
        assertEquals(label, expected, actual)
    }

    private fun trimmed(payload: ByteArray): ByteArray {
        var end = payload.size
        while (end > 1 && payload[end - 1].toInt() == 0) end--
        return payload.copyOf(end)
    }

    private fun className(name: String) =
        name.split('_').filter { it.isNotEmpty() }.joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }

    private fun propertyName(name: String): String {
        val parts = name.split('_').filter { it.isNotEmpty() }
        return parts.first() + parts.drop(1).joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
    }
}
