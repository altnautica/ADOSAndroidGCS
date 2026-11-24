package com.altnautica.gcs.feature.configure.wizard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.agent.fcsetup.SetupAction
import com.altnautica.gcs.core.agent.fcsetup.SetupActionStyle
import com.altnautica.gcs.core.agent.fcsetup.SetupConfirm
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The generic setup step renderer: each confirm tier, blockers, live values and the result card. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WizardStepViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = mapOf(
        "setup.outputs.title" to "Motors and outputs",
        "setup.outputs.body" to "Each motor spins briefly.",
        "setup.action.test" to "Run test",
        "setup.action.cancel" to "Cancel",
        "setup.action.abort" to "Abort",
        "setup.action.retry" to "Retry",
        "setup.confirm.hold_props_off" to "Propellers are removed",
        "setup.live.motor_number" to "Motor",
        "setup.live.question_key" to "Question",
        "setup.question.motor_direction" to "Is it spinning the right way?",
        "setup.blocker.swap_motor_wires" to "Swap two motor wires",
        "setup.result.gains_after" to "Gains after",
        "setup.ados_intro.title" to "Meet ADOS Pilot",
        "setup.ados_intro.body" to "ADOS flies the aircraft.",
        "setup.ados_intro.body.no_rc" to "Fly from the app.",
        "setup.action.use_ados" to "Use ADOS Pilot",
        "setup.action.use_ados_no_rc" to "Use ADOS Pilot with no RC",
        "setup.action.keep_fc" to "Keep the flight controller",
        "setup.ados_profile.title" to "ADOS safety profile",
        "setup.ados_profile.params" to "Values to write",
        "setup.ados_profile.param.AVOID_ENABLE" to "Backstop: proximity stop (2)",
        "setup.ados_profile.param.OA_TYPE" to "Flight controller path planning off (0)",
        "setup.result.applied" to "Written",
    )
    private val text = SetupText { key, args -> strings[key]?.let { s -> args.entries.fold(s) { t, (k, v) -> t.replace("{$k}", v) } } ?: key }

    private val fired = mutableListOf<String>()

    private fun show(step: SetupStep, busy: Boolean = false) = compose.setContent {
        AdosTheme {
            CompositionLocalProvider(LocalSetupText provides text) {
                WizardStepView(step, busy = busy, onAction = { a, _ -> fired += a })
            }
        }
    }

    private fun step(
        actions: List<SetupAction>,
        blockers: List<String> = emptyList(),
        live: Map<String, String> = emptyMap(),
        result: JsonObject? = null,
        state: SetupStepState = SetupStepState.WaitingUser,
    ) = SetupStep(
        wizard = "ground",
        step = "outputs",
        titleKey = "setup.outputs.title",
        bodyKey = "setup.outputs.body",
        state = state,
        live = JsonObject(live.mapValues { JsonPrimitive(it.value) }),
        actions = actions,
        blockers = blockers,
        result = result,
    )

    @Test
    fun plainActionFiresOnTap() {
        show(step(listOf(SetupAction("cancel", "setup.action.cancel", SetupActionStyle.Secondary, SetupConfirm.None))))
        compose.onNodeWithText("Motors and outputs").assertExists()
        compose.onNodeWithText("Each motor spins briefly.").assertExists()
        compose.onNodeWithTag("setup-action-cancel").assertIsEnabled().performClick()
        assertEquals(listOf("cancel"), fired)
    }

    @Test
    fun holdActionRendersAHoldConfirm() {
        show(step(listOf(SetupAction("abort", "setup.action.abort", SetupActionStyle.Danger, SetupConfirm.Hold))))
        compose.onNodeWithText("Hold: Abort").assertExists()
        // A tap is not a hold: nothing fires.
        compose.onNodeWithTag("setup-action-abort").performClick()
        assertEquals(emptyList<String>(), fired)
    }

    @Test
    fun propsOffActionShowsItsLabelAndThePropellersRemovedHold() {
        show(step(listOf(SetupAction("test", "setup.action.test", SetupActionStyle.Primary, SetupConfirm.HoldPropsOff))))
        compose.onNodeWithText("Run test").assertExists()
        compose.onNodeWithText("Propellers are removed").assertExists()
        compose.onNodeWithTag("setup-action-test").assertIsEnabled()
    }

    @Test
    fun blockersShowAndDisableEveryAction() {
        show(
            step(
                listOf(SetupAction("retry", "setup.action.retry", SetupActionStyle.Primary, SetupConfirm.None)),
                blockers = listOf("setup.blocker.swap_motor_wires"),
                state = SetupStepState.Failed,
            ),
        )
        compose.onNodeWithText("Swap two motor wires").assertExists()
        compose.onNodeWithTag("setup-action-retry").assertIsNotEnabled()
    }

    @Test
    fun busyDisablesActions() {
        show(step(listOf(SetupAction("cancel", "setup.action.cancel", SetupActionStyle.Secondary, SetupConfirm.None))), busy = true)
        compose.onNodeWithTag("setup-action-cancel").assertIsNotEnabled()
    }

    @Test
    fun bareLiveKeysAndKeyValuesResolveToTheirStrings() {
        show(
            step(
                listOf(SetupAction("cancel", "setup.action.cancel", SetupActionStyle.Secondary, SetupConfirm.None)),
                live = mapOf("motor_number" to "4", "question_key" to "setup.question.motor_direction"),
            ),
        )
        compose.onNodeWithText("Motor").assertExists()
        compose.onNodeWithText("Question").assertExists()
        compose.onNodeWithText("Is it spinning the right way?").assertExists()
    }

    @Test
    fun resultRendersUnderItsLabel() {
        show(
            step(
                emptyList(),
                result = JsonObject(mapOf("gains_after" to JsonPrimitive("0.12"), "tested" to JsonPrimitive(4))),
                state = SetupStepState.Done,
            ),
        )
        compose.onNodeWithText("Results").assertExists()
        compose.onNodeWithText("Gains after").assertExists()
        compose.onNodeWithText("0.12").assertExists()
        compose.onNodeWithText("tested").assertExists()
        compose.onNodeWithText("4").assertExists()
    }

    @Test
    fun plannedParamsListWithWhatEachIsForAndAppliedRowsReadAsAssignments() {
        val rows = listOf("AVOID_ENABLE" to 2, "AVOID_MARGIN" to 1.3, "OA_TYPE" to 0).map { (n, v) ->
            JsonObject(mapOf("name" to JsonPrimitive(n), "value" to JsonPrimitive(v)))
        }
        val step = SetupStep(
            wizard = "ground",
            step = "ados_profile",
            titleKey = "setup.ados_profile.title",
            args = JsonObject(mapOf("params" to JsonArray(rows))),
            state = SetupStepState.Done,
            result = JsonObject(mapOf("applied" to JsonArray(rows.take(2)))),
        )
        assertEquals(listOf("AVOID_ENABLE", "AVOID_MARGIN", "OA_TYPE"), step.paramRows().map { it.name })
        show(step)
        // The rows sit folded under "What this changes".
        compose.onNodeWithTag(SETUP_PARAMS_TAG).assertDoesNotExist()
        compose.onNodeWithTag("setup-changes").performClick()
        compose.onNodeWithTag(SETUP_PARAMS_TAG).assertExists()
        compose.onNodeWithText("Values to write").assertExists()
        compose.onNodeWithText("Backstop: proximity stop (2)").assertExists()
        compose.onNodeWithText("Flight controller path planning off (0)").assertExists()
        compose.onNodeWithText("1.3").assertExists()
        compose.onNodeWithText("Written").assertExists()
        compose.onNodeWithText("AVOID_ENABLE = 2, AVOID_MARGIN = 1.3").assertExists()
    }

    private fun intro(rcPresent: Boolean?, noRcOffered: Boolean = true) = SetupStep(
        wizard = "ground",
        step = ADOS_INTRO_STEP,
        titleKey = "setup.ados_intro.title",
        bodyKey = "setup.ados_intro.body",
        args = JsonObject(listOfNotNull("fallback_mode" to JsonPrimitive("elrs"), rcPresent?.let { "rc_present" to JsonPrimitive(it) }).toMap()),
        state = SetupStepState.WaitingUser,
        actions = listOfNotNull(
            SetupAction("use_ados", "setup.action.use_ados", SetupActionStyle.Primary, SetupConfirm.None),
            SetupAction("keep_fc", "setup.action.keep_fc", SetupActionStyle.Secondary, SetupConfirm.None),
            SetupAction(USE_ADOS_NO_RC, "setup.action.use_ados_no_rc", SetupActionStyle.Secondary, SetupConfirm.None).takeIf { noRcOffered },
        ),
    )

    @Test
    fun noRcChoiceLeadsWithoutAReceiverAndShowsItsBody() {
        val shown = shownActions(intro(rcPresent = false))
        assertEquals(listOf(USE_ADOS_NO_RC, "use_ados", "keep_fc"), shown.map { it.id })
        assertEquals(listOf(SetupActionStyle.Primary, SetupActionStyle.Secondary, SetupActionStyle.Secondary), shown.map { it.style })

        show(intro(rcPresent = false))
        compose.onNodeWithText("• Fly from the app.").assertExists()
        compose.onNodeWithTag("setup-action-$USE_ADOS_NO_RC").performClick()
        assertEquals(listOf(USE_ADOS_NO_RC), fired)
    }

    @Test
    fun withAReceiverAdosLeadsAndTheNoRcChoiceIsSecondary() {
        val shown = shownActions(intro(rcPresent = true))
        assertEquals(listOf("use_ados", "keep_fc", USE_ADOS_NO_RC), shown.map { it.id })
        assertEquals(SetupActionStyle.Primary, shown.first().style)
        assertEquals(SetupActionStyle.Secondary, shown.last().style)
    }

    @Test
    fun noRcBodyShowsOnlyWhenItsChoiceIsOffered() {
        val older = intro(rcPresent = null, noRcOffered = false)
        assertEquals(older.actions, shownActions(older))
        show(older)
        compose.onNodeWithText("• ADOS flies the aircraft.").assertExists()
        compose.onNodeWithText("• Fly from the app.").assertDoesNotExist()
    }
}
