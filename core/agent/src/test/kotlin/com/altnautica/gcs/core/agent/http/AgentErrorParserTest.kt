package com.altnautica.gcs.core.agent.http

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentErrorParserTest {

    @Test
    fun `armed conflict carries the force override`() {
        assertEquals(
            AgentError.Armed(canForce = true),
            AgentErrorParser.parse(409, """{"error":"E_ARMED","message":"Vehicle is armed","override":"force"}"""),
        )
        assertEquals(AgentError.Armed(canForce = false), AgentErrorParser.parse(409, """{"error":"E_ARMED"}"""))
    }

    @Test
    fun `nested detail error code maps to profile mismatch`() {
        assertEquals(
            AgentError.ProfileMismatch,
            AgentErrorParser.parse(404, """{"detail":{"error":{"code":"E_PROFILE_MISMATCH"}}}"""),
        )
    }

    @Test
    fun `relay ticket and peer errors`() {
        assertEquals(
            AgentError.RelayTicket("no_secret"),
            AgentErrorParser.parse(401, """{"error":"E_RELAY_TICKET","reason":"no_secret"}""", relayed = true),
        )
        assertEquals(
            AgentError.PeerApi("E_PEER_NOT_LINKED", "not linked"),
            AgentErrorParser.parse(404, """{"detail":{"error":{"code":"E_PEER_NOT_LINKED","message":"not linked"}}}"""),
        )
        assertEquals(
            AgentError.PeerApi(null, "relay timed out"),
            AgentErrorParser.parse(504, """{"detail":"relay timed out"}""", relayed = true),
        )
    }

    @Test
    fun `plain detail string, unauthorized and pin`() {
        assertEquals(AgentError.Server(400, "relay path is missing"), AgentErrorParser.parse(400, """{"detail":"relay path is missing"}"""))
        assertEquals(AgentError.Unauthorized, AgentErrorParser.parse(401, """{"detail":"Invalid API key"}"""))
        assertEquals(
            AgentError.PinRequired("This device is not paired yet. Set up access with the dashboard PIN, or pair it first."),
            AgentErrorParser.parse(403, """{"detail":"This device is not paired yet. Set up access with the dashboard PIN, or pair it first."}"""),
        )
        assertEquals(AgentError.Unauthorized, AgentErrorParser.parse(403, """{"detail":"This device is not paired yet. Pair it first."}"""))
    }

    @Test
    fun `missing routes are feature absent`() {
        assertEquals(AgentError.FeatureAbsent, AgentErrorParser.parse(404, """{"detail":"Not Found"}"""))
        assertEquals(AgentError.FeatureAbsent, AgentErrorParser.parse(501, ""))
        assertEquals(AgentError.Server(500, null), AgentErrorParser.parse(500, "<html>"))
    }
}
