package com.altnautica.gcs.feature.cockpit.modes

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Control-mode and avoidance-off changes on the Mode sheet go through only on a completed hold. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModeSheetConfirmTest {
    @get:Rule
    val compose = createComposeRule()

    private val spec = testSpec.flightState.modeSheet
    private val sent = mutableListOf<String>()
    private val actions = object : ModeSheetActions {
        override fun onProfile(profile: PilotProfile) { sent += "profile:${profile.wire}" }
        override fun onControlMode(mode: ControlMode) { sent += "control:${mode.wire}" }
        override fun onAvoid(mode: AvoidMode) { sent += "avoid:${mode.wire}" }
        override fun onHandToFc(mode: String) { sent += "hand:$mode" }
        override fun onTakeControl() { sent += "take" }
        override fun onFcMode(name: String) { sent += "fc:$name" }
        override fun onVtol(toFixedWing: Boolean) { sent += if (toFixedWing) "vtol:fw" else "vtol:mc" }
        override fun onDismiss() = Unit
    }

    private fun show(ui: ModeSheetUi) = compose.setContent {
        AdosTheme {
            androidx.compose.runtime.CompositionLocalProvider(com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings provides testSpec.setupStrings) {
                ModeSheet(ui, spec, actions, testSpec.layout.holdsMs)
            }
        }
    }
    private val ui = ModeSheetUi(
        profile = null,
        controlMode = ControlMode.Ados,
        avoid = AvoidMode.Brake,
        owner = PilotOwner.Pilot,
        vehicleClass = null,
        elrsAvailable = false,
        pilotLinked = true,
        fcModes = ModePickerUi(emptyList(), enabled = true, reasonKey = null),
    )

    private fun hold(tag: String, ms: Long) {
        compose.onNodeWithTag(tag).performScrollTo()
        // A manual clock stepped a frame at a time, idling the main thread after each so the
        // ring animates from the press; an auto-advancing clock would idle it to the end.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(tag).performTouchInput { down(center) }
        repeat((ms / FRAME_MS).toInt()) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        // Lifted through the root: a completed hold removes its button from the sheet.
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private companion object {
        const val FRAME_MS = 16L
    }

    @Test
    fun `a control-mode pick needs the full hold`() {
        show(ui)
        compose.onNodeWithTag("$TAG_CONTROL:fc_manual").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("picking alone sends nothing", sent.isEmpty())
        hold(TAG_CONTROL_HOLD, spec.controlMode.confirm.ms / 3L)
        assertTrue("a short press sends nothing", sent.isEmpty())
        hold(TAG_CONTROL_HOLD, spec.controlMode.confirm.ms + 200L)
        assertEquals(listOf("control:fc_manual"), sent)
    }

    @Test
    fun `avoidance off needs the full hold, other avoidance picks go at once`() {
        show(ui)
        compose.onNodeWithTag("$TAG_AVOID:bypass").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("avoid:bypass"), sent)
        compose.onNodeWithTag("$TAG_AVOID:off").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, sent.size)
        hold(TAG_AVOID_HOLD, spec.avoidance.confirmOff.ms + 200L)
        assertEquals(listOf("avoid:bypass", "avoid:off"), sent)
    }

    @Test
    fun `a VTOL airframe gets the transition behind its hold`() {
        show(ui.copy(vehicleClass = com.altnautica.gcs.core.model.PilotVehicleClass.Vtol))
        hold("$TAG_VTOL:fw", testSpec.layout.holdsMs.getValue(HOLD_VTOL) + 200L)
        assertEquals(listOf("vtol:fw"), sent)
    }

    @Test
    fun `a multirotor shows no VTOL section`() {
        show(ui.copy(vehicleClass = com.altnautica.gcs.core.model.PilotVehicleClass.Multirotor))
        compose.onNodeWithTag("$TAG_VTOL:fw").assertDoesNotExist()
    }
}
