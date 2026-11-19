package com.altnautica.gcs.core.link.relay

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Relay tickets against the tokens the agent's own issuer printed (`ground_vectors_relay_tickets`). */
class RelayTicketVectorTest {
    private val lines = checkNotNull(javaClass.classLoader!!.getResource("ground-vectors/relay-tickets.txt")).readText().lines()

    private fun entry(tag: String, name: String): String =
        lines.first { it.startsWith("$tag $name ") }.substringAfter("$tag $name ")

    private val ticket = RelayTicket.forSecret("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")

    private fun mint(method: String, path: String, body: ByteArray) =
        ticket.mint("drone-1", method, path, body, 1_700_000_000, "0f1e2d3c4b5a69788796a5b4c3d2e1f0")

    @Test
    fun `tickets are byte-identical to the agent's`() {
        assertEquals(entry("TICKET", "ticket_get_status"), mint("GET", "/api/status", ByteArray(0)))
        assertEquals(
            entry("TICKET", "ticket_put_config"),
            mint("PUT", "/api/config", """{"key":"video.wfb.fleet_slot","value":3}""".toByteArray()),
        )
        val op = buildJsonObject {
            put("op", JsonPrimitive("put"))
            put("key", JsonPrimitive("video.wfb.bitrate"))
            put("value", JsonPrimitive("8000"))
        }
        val binding = RelayTicket.tunnelBindingBody(op)
        assertEquals(entry("VECTOR", "tunnel_binding_put"), binding.joinToString("") { "%02x".format(it) })
        assertEquals(entry("TICKET", "ticket_tunnel"), mint(RelayTicket.TUNNEL_METHOD, RelayTicket.TUNNEL_PATH, binding))
    }

    @Test
    fun `an absent value binds as empty`() {
        val get = buildJsonObject {
            put("op", JsonPrimitive("get"))
            put("key", JsonPrimitive("radio"))
        }
        assertEquals(entry("VECTOR", "tunnel_binding_get"), RelayTicket.tunnelBindingBody(get).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun `a fresh nonce is 32 hex characters`() {
        val n = RelayTicket.newNonce()
        assertEquals(32, n.length)
        assertEquals(n, n.filter { it in "0123456789abcdef" })
    }
}
