package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** The overview's cards, progress and primary button, as `setup-wizard/overview.ts`. */
class SetupOverviewTest {
    private fun step(id: String, state: SetupStepState, result: JsonObject? = null, blockers: List<String> = emptyList()) =
        SetupStep("ground", id, "setup.$id.title", state = state, result = result, blockers = blockers)

    private val text = SetupText { key, args ->
        when (key) {
            "setup.summary.prop_in" -> "${args["v"]} in props"
            "setup.input.frame_class.1" -> "Quad"
            "setup.card.state.not_needed" -> "Not needed"
            else -> key
        }
    }

    @Test
    fun theGroundWizardGroupsIntoComponentCardsAndHidesDetect() {
        val w = SetupWizard(
            "ground",
            state = SetupWizardState.Interrupted,
            steps = listOf(
                step("detect", SetupStepState.Done),
                step("frame", SetupStepState.Done, JsonObject(mapOf("frame_class" to JsonPrimitive(1), "prop_in" to JsonPrimitive(5)))),
                step("accel", SetupStepState.Failed),
                step("level", SetupStepState.Pending),
                step("compass", SetupStepState.Skipped),
                step("radio", SetupStepState.WaitingUser),
                step("notch_static", SetupStepState.Skipped),
                step("ados_intro", SetupStepState.Pending),
            ),
        )
        val cards = SetupOverview.cards(w)
        assertEquals(listOf("frame", "sensors", "radio", "flight", "ados"), cards.map { it.id })
        assertEquals(
            listOf(CardState.Done, CardState.Attention, CardState.InProgress, CardState.NotNeeded, CardState.Optional),
            cards.map { it.state },
        )
        assertEquals("Quad · 5 in props", SetupOverview.summary(cards[0], text))
        assertEquals("Not needed", SetupOverview.summary(cards[3], text))
        // The sensors card opens its first unfinished step.
        assertEquals("accel", SetupOverview.target(cards[1]).step)
        // detect is not shown or counted: frame, compass and the notch are finished.
        assertEquals(3 to 7, SetupOverview.progress(w))
        assertEquals(PrimaryKind.Resume, SetupOverview.primary(w.state))
    }

    @Test
    fun aBlockedUnfinishedStepNeedsAttentionAndTheButtonFollowsTheWizard() {
        assertEquals(CardState.Attention, SetupOverview.cardState(listOf(step("battery", SetupStepState.Pending, blockers = listOf("setup.blocker.armed")))))
        assertEquals(PrimaryKind.Start, SetupOverview.primary(SetupWizardState.NotStarted))
        assertEquals(PrimaryKind.Continue, SetupOverview.primary(SetupWizardState.Running))
        assertEquals(PrimaryKind.RunAgain, SetupOverview.primary(SetupWizardState.Done))
    }

    @Test
    fun anotherWizardIsOneCardPerStep() {
        val air = SetupWizard("air", steps = listOf(SetupStep("air", "preflight", "setup.preflight.title"), SetupStep("air", "verify", "setup.verify.title")))
        assertEquals(listOf("preflight", "verify"), SetupOverview.cards(air).map { it.id })
    }

    @Test
    fun anEnumInputKeepsItsPlainOptionsAndReadsAPicturePerOption() {
        val spec = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"inputs":{"frame_type":{"kind":"enum","label_key":"setup.input.frame_type","value":1,"options":[0,1],
               "images":{"0":"frame_quad_plus","1":"frame_quad_x"}}}}""",
        ) as JsonObject
        val input = SetupInput.of(SetupStep("ground", "frame", "setup.frame.title", args = spec)).single()
        assertEquals(listOf(JsonPrimitive(0), JsonPrimitive(1)), input.options)
        assertEquals(mapOf("0" to "frame_quad_plus", "1" to "frame_quad_x"), input.images)
    }
}
