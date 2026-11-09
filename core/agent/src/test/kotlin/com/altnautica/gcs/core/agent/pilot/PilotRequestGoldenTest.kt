package com.altnautica.gcs.core.agent.pilot

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The client frames this app sends on the pilot channel, against the golden request
 * frames the agent's pilot stream decodes in its own tests (a byte copy of the agent's
 * `crates/ados-control/tests/fixtures/pilot-requests.json`).
 */
class PilotRequestGoldenTest {
    private val golden = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader!!.getResource("fixtures/pilot-requests.json")).readText(),
    ) as JsonObject

    private fun assertFrame(key: String, sent: String) =
        assertEquals(key, golden.getValue(key), Json.parseToJsonElement(sent))

    @Test
    fun `stick input matches the agent's decoder fixture`() =
        assertFrame("input", PilotWire.input(PilotWire.SRC_ANDROID, 7, 12, 3400, PilotInput(0.25f, -1f, 0f, 0.5f, 65537)))

    @Test
    fun `stick release matches the agent's decoder fixture`() =
        assertFrame("release", PilotWire.release(PilotWire.SRC_ANDROID, 7))

    @Test
    fun `session take-over matches the agent's decoder fixture`() =
        assertFrame("session_take", PilotWire.sessionTake(PilotWire.SRC_ANDROID, 8))

    @Test
    fun `heartbeat matches the agent's decoder fixture`() =
        assertFrame("hb", PilotWire.hb(PilotWire.SRC_ANDROID))

    @Test
    fun `an action with arguments and an id matches the agent's decoder fixture`() =
        assertFrame("action", PilotWire.action(PilotAction(PilotAction.HAND_TO_FC, mapOf("mode" to "loiter")), PilotWire.SRC_ANDROID, "3"))
}
