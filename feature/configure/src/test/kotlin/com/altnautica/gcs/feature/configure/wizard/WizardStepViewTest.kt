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
}
