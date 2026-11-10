package com.altnautica.gcs.feature.cockpit.flow

import com.altnautica.gcs.core.model.LaunchSequence
import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.feature.cockpit.pilotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowUiTest {
    @Test
    fun `a running launch stays on screen until it is done`() {
        val climbing = LaunchSequence(LaunchStep.Takeoff, "hold", null)
        val pilot = pilotState(PilotOwner.Idle, armed = true, landed = false).copy(sequence = climbing)
        assertEquals(climbing, FlowUi.of(pilot, "android", streaming = false).launch)
        assertNull(FlowUi.of(pilot.copy(sequence = climbing.copy(step = LaunchStep.Done)), "android", streaming = false).launch)
        assertNull(FlowUi.of(pilot.copy(sequence = null), "android", streaming = false).launch)
    }

    @Test
    fun `countdowns round up like every other surface`() {
        assertEquals(5, countdownSeconds(4.2f))
        assertEquals(5, countdownSeconds(5f))
        assertEquals(1, countdownSeconds(0.1f))
        assertEquals(0, countdownSeconds(0f))
        assertEquals(0, countdownSeconds(-1f))
    }
}
