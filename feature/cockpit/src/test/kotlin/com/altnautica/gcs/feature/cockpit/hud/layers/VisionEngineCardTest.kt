package com.altnautica.gcs.feature.cockpit.hud.layers

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The vision panel's engine card and the layer badges' reasons. */
class VisionEngineCardTest {
    private val running = EngineStatus("ready", "onnx", emptyList())
    private val down = EngineStatus("unavailable", null, emptyList())

    @Test
    fun `the card reads running, starting, off, unreachable and a start that timed out`() {
        assertEquals(EngineCardState.Running, engineCardState(true, running, false, 5L, 10L))
        assertEquals(EngineCardState.Starting, engineCardState(true, null, true, 0L, VISION_START_MS - 1))
        assertEquals(EngineCardState.StartTimeout, engineCardState(true, null, true, 0L, VISION_START_MS))
        assertEquals(EngineCardState.Off, engineCardState(false, null, true, null, 0L))
        assertEquals(EngineCardState.Unreachable, engineCardState(true, null, true, null, 0L))
        assertEquals(EngineCardState.Unavailable, engineCardState(true, down, false, null, 0L))
        assertEquals(EngineCardState.Checking, engineCardState(null, null, false, null, 0L))
    }

    @Test
    fun `a restart the agent does not serve is a refusal, not a failure`() {
        val ok = AgentResult.Ok(buildJsonObject { put("status", JsonPrimitive("ok")) })
        val unknown = AgentResult.Ok(buildJsonObject { put("message", JsonPrimitive("Unknown service ados-vision")) })
        assertEquals(VisionChange.Ok, visionRestart(ok))
        assertEquals(VisionChange.Refused, visionRestart(unknown))
        assertEquals(VisionChange.Refused, visionRestart(AgentResult.Err(AgentError.Server(403, "no"))))
        assertEquals(VisionChange.Failed("boom"), visionRestart(AgentResult.Err(AgentError.Server(500, "boom"))))
        assertEquals(VisionChange.Failed(null), visionRestart(null))
    }

    @Test
    fun `a vision layer reads engine off only while the engine does not answer`() {
        assertEquals(BadgeReason.EngineOff, badgeReason(LayerSpec.DEPTH, engineUp = false))
        assertEquals(BadgeReason.NoData, badgeReason(LayerSpec.DEPTH, engineUp = true))
        assertEquals(BadgeReason.NoData, badgeReason(LayerSpec.DEPTH, engineUp = null))
        // Obstacles and AR come from navigation, not the vision engine.
        assertEquals(BadgeReason.NoData, badgeReason(LayerSpec.OBSTACLES, engineUp = false))
    }
}
