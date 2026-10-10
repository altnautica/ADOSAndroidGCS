package com.altnautica.gcs.core.extensions.bridge

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvelopeDispatcherTest {

    private val posted = mutableListOf<JsonObject>()
    private val security = mutableListOf<String>()
    private var granted = setOf<String>()
    private var calls = 0

    private val dispatcher = EnvelopeDispatcher(
        pluginId = "com.example.ext",
        granted = { granted },
        handlers = mapOf<String, BridgeHandler>(
            "ping" to { _ -> calls++; JsonPrimitive("pong") },
            "telemetry.subscribe" to { _ -> calls++; JsonPrimitive(capability) },
            "command.send" to { _ -> calls++; JsonPrimitive(capability) },
            "mission.read" to { _ -> throw BridgeException("unsupported_on_platform", "nope") },
        ),
        post = { posted += it },
        onSecurityEvent = { code, _ -> security += code },
    )

    private fun request(method: String, args: String = "null") = Json.parseToJsonElement(
        """{"id":"r1","type":"request","method":"$method","capability":"","args":$args,"version":1}""",
    )

    private fun errorCode(): String? = posted.last()["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    @Test
    fun alwaysAllowedMethodRuns() = runTest {
        dispatcher.dispatch(request("ping"))
        assertEquals("pong", posted.single()["args"]!!.jsonPrimitive.content)
        assertEquals("response", posted.single()["type"]!!.jsonPrimitive.content)
        assertNull(errorCode())
    }

    @Test
    fun gatedMethodIsDeniedWithoutCapability() = runTest {
        dispatcher.dispatch(request("telemetry.subscribe", """{"topic":"mavlink.attitude"}"""))
        assertEquals("permission_denied", errorCode())
        assertEquals(0, calls)
        assertEquals(listOf("permission_denied"), security)
    }

    @Test
    fun perTopicCapabilityIsExact() = runTest {
        granted = setOf("telemetry.subscribe")
        dispatcher.dispatch(request("telemetry.subscribe", """{"topic":"mavlink.attitude"}"""))
        assertEquals("permission_denied", errorCode())

        granted = setOf("telemetry.subscribe.mavlink.attitude")
        dispatcher.dispatch(request("telemetry.subscribe", """{"topic":"mavlink.attitude"}"""))
        assertNull(errorCode())
        assertEquals("telemetry.subscribe.mavlink.attitude", posted.last()["args"]!!.jsonPrimitive.content)
    }

    @Test
    fun designateNeedsItsOwnCapability() = runTest {
        granted = setOf("command.send")
        dispatcher.dispatch(request("command.send", """{"command":"vision.designate","args":{}}"""))
        assertEquals("permission_denied", errorCode())
        dispatcher.dispatch(request("command.send", """{"command":"land"}"""))
        assertNull(errorCode())
        granted = setOf("vision.track.designate")
        dispatcher.dispatch(request("command.send", """{"command":"vision.designate","args":{}}"""))
        assertEquals("vision.track.designate", posted.last()["args"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownMethodAndMissingTopicAreRejected() = runTest {
        dispatcher.dispatch(request("toString"))
        assertEquals("method_unknown", errorCode())
        granted = setOf("event.subscribe")
        dispatcher.dispatch(request("events.subscribe", "{}"))
        assertEquals("schema_invalid", errorCode())
    }

    @Test
    fun malformedEnvelopesAreDroppedSilently() = runTest {
        dispatcher.dispatch(Json.parseToJsonElement("""{"id":"x","type":"request","method":"ping","capability":"","version":2}"""))
        dispatcher.dispatch(Json.parseToJsonElement("""["ping"]"""))
        dispatcher.dispatch(Json.parseToJsonElement("""{"id":"","type":"request","method":"ping","capability":"","version":1}"""))
        assertTrue(posted.isEmpty())
        assertEquals(3, security.count { it == "schema_invalid" })
    }

    @Test
    fun responsesAndEventsFromTheFrameAreIgnored() = runTest {
        dispatcher.dispatch(Json.parseToJsonElement("""{"id":"x","type":"event","method":"ping","capability":"","version":1}"""))
        assertTrue(posted.isEmpty())
    }

    @Test
    fun handlerErrorsCarryTheirCode() = runTest {
        granted = setOf("mission.read")
        dispatcher.dispatch(request("mission.read"))
        assertEquals("unsupported_on_platform", errorCode())
    }

    @Test
    fun knownButUnhandledMethodReportsHandlerUnset() = runTest {
        granted = setOf("cloud.read")
        dispatcher.dispatch(request("cloud.read"))
        assertEquals("handler_unset", errorCode())
    }
}
