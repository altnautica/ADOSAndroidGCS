package com.altnautica.gcs.feature.configure.wizard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.altnautica.gcs.core.designsystem.component.console.ConsoleCommandDispatcher
import com.altnautica.gcs.core.designsystem.component.console.LocalConsoleCommands
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.agent.fcsetup.FcSetupApi
import com.altnautica.gcs.core.agent.fcsetup.FcSetupOverview
import com.altnautica.gcs.core.agent.fcsetup.SetupRunning
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.ParamDiff
import com.altnautica.gcs.core.model.ReadinessFix
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The setup refinements ported from the shared web overview: a live run outranks the filed
 * state, a wizard filed as done counts its unrecorded steps, readiness flags cards, and the
 * confirmations (Run again and the Aircraft-setup Abort hold; Flight tuning's Abort does not).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SetupRefinementsTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = mapOf("setup.action.abort" to "Abort", "setup.overview.continue" to "Continue setup")
    private val text = SetupText { key, _ -> strings[key] ?: key }

    private fun step(id: String, state: SetupStepState, wizard: String = "ground", blockers: List<String> = emptyList()) =
        SetupStep(wizard, id, "setup.$id.title", state = state, blockers = blockers)

    // ---- (a) a top-level `running` is a run in progress ---------------------------------------

    @Test
    fun aLiveRunOfAFiledWizardOffersContinueAtItsStep() {
        val done = SetupWizard("ground", state = SetupWizardState.Done, steps = listOf(step("frame", SetupStepState.Done), step("accel", SetupStepState.Running)))
        val overview = FcSetupOverview(uid = "fc", firmware = "ardupilot", running = SetupRunning("ground", "accel"), wizards = listOf(done))
        assertEquals("accel", SetupOverview.runningStep(overview, done))
        assertEquals(PrimaryKind.Continue, SetupOverview.primary(SetupWizardState.Done, running = true))
        assertEquals(PrimaryKind.Continue, SetupOverview.primary(SetupWizardState.Aborted, running = true))
        assertEquals(PrimaryKind.RunAgain, SetupOverview.primary(SetupWizardState.Done))

        val ui = WizardUiState(agent = true, setup = SetupSnapshotState(overview = overview), focus = WizardFocus("ground"))
        assertFalse(ui.showResults)
        assertEquals("accel", ui.step!!.step)
    }

    @Test
    fun blockersNeverHideTheWayIntoALiveRun() {
        val wizard = SetupWizard(
            "ground",
            state = SetupWizardState.Done,
            blockers = listOf("setup.blocker.armed"),
            steps = listOf(step("frame", SetupStepState.Done), step("accel", SetupStepState.Running)),
        )
        val overview = FcSetupOverview(uid = "fc", firmware = "ardupilot", running = SetupRunning("ground", "accel"), wizards = listOf(wizard))
        val opened = mutableListOf<Pair<String, String?>>()
        screen(WizardUiState(agent = true, setup = SetupSnapshotState(overview = overview)), onOpen = { w, s -> opened += w to s })
        compose.onNodeWithTag("setup-primary-ground").assertTextContains("Continue setup").performClick()
        assertEquals(listOf("ground" to "accel"), opened)
    }

    // ---- (c) filed-done steps and readiness flags --------------------------------------------

    @Test
    fun aWizardFiledAsDoneCountsItsUnrecordedStepsAsDone() {
        val filed = SetupWizard("ground", state = SetupWizardState.Done, steps = listOf(step("frame", SetupStepState.Pending), step("battery", SetupStepState.Pending)))
        assertEquals(2 to 2, SetupOverview.progress(filed))
        assertEquals(listOf(CardState.Done, CardState.Done), SetupOverview.cards(filed).map { it.state })
        // Not filed as done: the same steps are unset.
        assertEquals(0 to 2, SetupOverview.progress(filed.copy(state = SetupWizardState.NotStarted)))
    }

    @Test
    fun readinessFlagsTheSafetyAndTheNamedStepCardsWithTheReason() {
        val readiness = listOf(
            ReadinessItem(
                "fc_profile", ok = false, severity = ReadinessSeverity.Block, msg = "FC parameters differ",
                params = listOf(ParamDiff("MAV_GCS_SYSID", 255.0, "1"), ParamDiff("PRX1_TYPE", null, "2")),
                fix = ReadinessFix.ApplyProfile,
            ),
            ReadinessItem("firmware", ok = false, severity = ReadinessSeverity.Warn, msg = "Firmware unknown", fix = ReadinessFix.SetupStep("battery")),
            ReadinessItem("flow", ok = false, severity = ReadinessSeverity.Warn, msg = "No flow", fix = ReadinessFix.Settings("sensors")),
            ReadinessItem("position", ok = true, severity = ReadinessSeverity.Block, msg = "ok", fix = ReadinessFix.SetupStep("frame")),
        )
        val flags = SetupOverview.readinessFlags(readiness)
        assertEquals(mapOf("ados_profile" to "MAV_GCS_SYSID 255 → 1 · PRX1_TYPE — → 2", "battery" to "Firmware unknown"), flags)

        val wizard = SetupWizard(
            "ground",
            state = SetupWizardState.Done,
            steps = listOf(step("frame", SetupStepState.Done), step("battery", SetupStepState.Done), step("ados_profile", SetupStepState.Done)),
        )
        val cards = SetupOverview.cards(wizard, flags).associateBy { it.id }
        assertEquals(CardState.Done, cards.getValue("frame").state)
        assertNull(cards.getValue("frame").flag)
        // The FC's own report outranks the stored "done".
        assertEquals(CardState.Attention, cards.getValue("safety").state)
        assertEquals("Firmware unknown", SetupOverview.summary(cards.getValue("battery"), text))
        assertEquals("ados_profile", SetupOverview.target(cards.getValue("safety"), flags).step)
    }

    @Test
    fun flightTuningWaitingOnSetupReadsAfterSetup() {
        val air = SetupWizard("air", blockers = listOf("setup.blocker.ground_not_done"), steps = listOf(step("verify", SetupStepState.Pending, wizard = "air")))
        assertEquals(listOf(CardState.AfterSetup), SetupOverview.cards(air).map { it.state })
    }

    // ---- (d) confirmations and the neutral cancel ---------------------------------------------

    @Test
    fun aCancelledStepIsNeutralNotAFailure() {
        val cancelled = step("accel", SetupStepState.Failed, blockers = listOf(SetupOverview.CANCELLED_BLOCKER))
        assert(SetupOverview.isCancelled(cancelled))
        assertEquals(CardState.NotSet, SetupOverview.cardState(listOf(cancelled)))
        assertEquals(0, failedSteps(SetupWizard("ground", steps = listOf(cancelled))))
        assertFalse(SetupOverview.isCancelled(step("accel", SetupStepState.Failed, blockers = listOf("setup.blocker.armed"))))

        compose.setContent {
            AdosTheme { CompositionLocalProvider(LocalSetupText provides text) { WizardStepView(cancelled, busy = false, onAction = { _, _ -> }) } }
        }
        compose.onNodeWithTag("setup-step-state").assertTextContains("Cancelled")
    }

    @Test
    fun abortingAircraftSetupNeedsAHoldAndATapDoesNothing() {
        var aborts = 0
        screen(runningOn(FcSetupApi.WIZARD_GROUND), onAbort = { aborts++ })
        compose.onNodeWithTag("setup-abort").assert(holdOnly).performClick()
        assertEquals(0, aborts)
    }

    @Test
    fun abortingFlightTuningIsOneTap() {
        var aborts = 0
        screen(runningOn(FcSetupApi.WIZARD_AIR), onAbort = { aborts++ })
        compose.onNodeWithTag("setup-abort").assertTextContains("Abort").performClick()
        assertEquals(1, aborts)
    }

    @Test
    fun runAgainNeedsAHold() {
        val done = SetupWizard("ground", state = SetupWizardState.Done, steps = listOf(step("frame", SetupStepState.Done)))
        val starts = mutableListOf<String>()
        screen(
            WizardUiState(agent = true, setup = SetupSnapshotState(overview = FcSetupOverview(uid = "fc", firmware = "ardupilot", wizards = listOf(done)))),
            onStart = { starts += it },
        )
        compose.onNodeWithTag("setup-primary-ground").assert(holdOnly).performClick()
        assertEquals(emptyList<String>(), starts)
    }

    private fun runningOn(wizard: String): WizardUiState {
        val w = SetupWizard(wizard, state = SetupWizardState.Running, steps = listOf(step("frame", SetupStepState.WaitingUser, wizard = wizard)))
        val overview = FcSetupOverview(uid = "fc", firmware = "ardupilot", running = SetupRunning(wizard, "frame"), wizards = listOf(w))
        return WizardUiState(agent = true, setup = SetupSnapshotState(overview = overview), focus = WizardFocus(wizard))
    }

    /** A hold-to-confirm control: a long press confirms, a tap has nothing to do. */
    private val holdOnly = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)

    private fun screen(
        state: WizardUiState,
        onOpen: (String, String?) -> Unit = { _, _ -> },
        onStart: (String) -> Unit = {},
        onAbort: () -> Unit = {},
    ) = compose.setContent {
        AdosTheme {
            CompositionLocalProvider(LocalSetupText provides text, LocalConsoleCommands provides remember { ConsoleCommandDispatcher() }) {
                AircraftSetupScreen(
                    state = state,
                    onBack = {},
                    onOpen = onOpen,
                    onStart = onStart,
                    onResume = {},
                    onRunStep = { _, _ -> },
                    onAction = { _, _, _ -> },
                    onAbort = onAbort,
                    onRevert = {},
                    onList = {},
                )
            }
        }
    }
}
