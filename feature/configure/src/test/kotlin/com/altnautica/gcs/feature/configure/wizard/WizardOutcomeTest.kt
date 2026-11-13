package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A finished wizard reads as a success only when no step failed. */
class WizardOutcomeTest {
    private fun step(id: String, state: SetupStepState) = SetupStep(wizard = "ground", step = id, titleKey = "setup.$id.title", state = state)

    @Test
    fun `a done wizard with failed calibrations is not a success`() {
        val wizard = SetupWizard(
            id = "ground",
            state = SetupWizardState.Done,
            steps = listOf(
                step("frame", SetupStepState.Done),
                step("accel", SetupStepState.Failed),
                step("level", SetupStepState.Skipped),
                step("compass", SetupStepState.Failed),
            ),
        )
        assertEquals(2, failedSteps(wizard))
        assertFalse(wizardSucceeded(wizard))
    }

    @Test
    fun `a done wizard with every step done or skipped is a success`() {
        val wizard = SetupWizard(
            id = "ground",
            state = SetupWizardState.Done,
            steps = listOf(step("frame", SetupStepState.Done), step("level", SetupStepState.Skipped)),
        )
        assertEquals(0, failedSteps(wizard))
        assertTrue(wizardSucceeded(wizard))
    }
}
