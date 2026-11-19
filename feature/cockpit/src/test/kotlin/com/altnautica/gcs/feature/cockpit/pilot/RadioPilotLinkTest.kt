package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PhoneRadioUplink
import com.altnautica.gcs.core.model.RadioCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio pilot link against the agent's pilot stream (`ground_vectors_pilot` in
 * ados-control): each client frame becomes the exact `pilot.v1` body, and each `state`
 * body becomes the frame's `data`.
 */
class RadioPilotLinkTest {
    private val lines = checkNotNull(javaClass.classLoader!!.getResource("ground-vectors/pilot.txt")).readText().lines()

    private fun entry(tag: String, name: String): String = lines.first { it.startsWith("$tag $name ") }.substringAfter("$tag $name ")

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `client frames become the stream's pilot bodies`() {
        for (key in listOf("input", "release", "session_take", "hb", "bye", "action", "goto")) {
            val frame = Json.parseToJsonElement(entry("PILOTJSON", key)).jsonObject
            assertArrayEquals(key, hex(entry("VECTOR", "pilot_$key")), RadioPilotLink.toPilot(frame))
        }
    }

    @Test
    fun `ops a surface may not send, and malformed frames, are refused`() {
        assertNull(RadioPilotLink.toPilot(Json.parseToJsonElement("""{"op":"lease.acquire","holder":{}}""").jsonObject))
        assertNull(RadioPilotLink.toPilot(Json.parseToJsonElement("""{"op":"input","src":"mc"}""").jsonObject))
    }

    @Test
    fun `state bodies become the stream's client frames`() {
        for (name in listOf("full", "minimal", "mixed")) {
            val text = RadioPilotLink.toClient(hex(entry("VECTOR", "state_$name")), nowMs = 42)!!
            val frame = Json.parseToJsonElement(text).jsonObject
            assertEquals("pilot", frame["kind"]!!.jsonPrimitive.content)
            assertEquals("42", frame["ts_ms"]!!.jsonPrimitive.content)
            assertTrue(name, same(Json.parseToJsonElement(entry("STATEJSON", name)), frame["data"]!!))
        }
    }

    @Test
    fun `input frames go up unbatched at the chosen rate and actions are answered locally`() = runBlocking {
        val sent = mutableListOf<ByteArray>()
        val radio = FakeRadio(sent)
        var t = 0L
        val link = RadioPilotLink(radio, DeviceId("drone-1"), { 20 }, nowMs = { 0 }, elapsedMs = { t })
        val input = entry("PILOTJSON", "input")
        val action = entry("PILOTJSON", "action")
        val out = link.open(
            flow {
                repeat(50) {
                    emit(input)
                    t += 20
                }
                emit(action)
                radio.transmitting.value = false
            },
        )
        val replies = runCatching { out.toList() }.exceptionOrNull()
        assertTrue("the link ends when the uplink drops", replies is java.io.IOException)
        // 50 frames over one second at 50 Hz, thinned to 20 Hz, plus the action.
        assertEquals(21, sent.size)
        assertArrayEquals(hex(entry("VECTOR", "pilot_action")), sent.last())
    }

    private class FakeRadio(private val sent: MutableList<ByteArray>) : PhoneRadioUplink {
        override val transmitting = MutableStateFlow(true)
        val down = MutableSharedFlow<ByteArray>()

        override fun hears(node: DeviceId) = true

        override fun sendPilot(node: DeviceId, body: ByteArray): Boolean = sent.add(body)

        override fun pilotFrom(node: DeviceId): Flow<ByteArray> = down

        override suspend fun call(node: DeviceId, method: String, path: String, contentType: String?, body: ByteArray) =
            RadioCall.Failed("unused")
    }

    /** JSON equality with numbers compared as numbers (a 32-bit float prints differently across runtimes). */
    private fun same(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject -> a.keys == b.keys && a.keys.all { same(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive && !a.isString && !b.isString ->
            a.content == b.content || (a.content.toDoubleOrNull() != null && a.content.toDoubleOrNull() == b.content.toDoubleOrNull())
        else -> a == b
    }
}
