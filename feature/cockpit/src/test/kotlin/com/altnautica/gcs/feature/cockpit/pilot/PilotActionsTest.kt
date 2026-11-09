package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.feature.cockpit.CockpitEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PilotActionsTest {
    private val log = mutableListOf<String>()

    private inner class Link(private val answer: PilotActionResult) : PilotLink {
        override val src = "android"
        override val connected = MutableStateFlow(true)
        override val state = MutableStateFlow<PilotState?>(null)
        override fun input(frame: PilotInput) = Unit
        override fun release() = Unit
        override suspend fun action(action: PilotAction): PilotActionResult {
            log += "action:${action.name}"
            return answer
        }
        override fun takeSession() = true
    }

    @Test
    fun `a Mode-sheet change stops the running motion skill before it reaches the pilot`() = runTest {
        val result = sendModeSheetAction(PilotAction.controlMode(ControlMode.FcManual), Link(PilotActionResult.Accepted)) { log += "stop" }
        assertEquals(PilotActionResult.Accepted, result)
        assertEquals(listOf("stop", "action:control_mode:fc_manual"), log)
    }

    @Test
    fun `the motion skill stops even when the pilot channel is gone`() = runTest {
        val result = sendModeSheetAction(PilotAction(PilotAction.HAND_TO_FC), null) { log += "stop" }
        assertEquals(PilotActionResult.Unavailable, result)
        assertEquals(listOf("stop"), log)
    }

    @Test
    fun `the pilot's answer becomes the cockpit event`() {
        assertNull(pilotActionEvent(PilotActionResult.Accepted))
        assertEquals(CockpitEvent.PilotRefused("armed"), pilotActionEvent(PilotActionResult.Refused("armed")))
        assertEquals(CockpitEvent.PilotNoAnswer, pilotActionEvent(PilotActionResult.NoAnswer))
        assertEquals(CockpitEvent.PilotUnavailable, pilotActionEvent(PilotActionResult.Unavailable))
    }
}
