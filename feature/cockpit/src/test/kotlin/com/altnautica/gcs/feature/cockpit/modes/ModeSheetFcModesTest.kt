package com.altnautica.gcs.feature.cockpit.modes

import android.view.KeyCharacterMap
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.component.CancelledConfirmGuard
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Mode sheet's advanced section lists every FC mode; a tap on one asks for it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ModeSheetFcModesTest {
    @get:Rule
    val compose = createComposeRule()

    private val picked = mutableListOf<String>()
    private val actions = object : ModeSheetActions {
        override fun onProfile(profile: PilotProfile) = Unit
        override fun onControlMode(mode: ControlMode) = Unit
        override fun onAvoid(mode: AvoidMode) = Unit
        override fun onHandToFc(mode: String) = Unit
        override fun onTakeControl() = Unit
        override fun onFcMode(name: String) { picked += name }
        override fun onVtol(toFixedWing: Boolean) = Unit
        override fun onDismiss() = Unit
    }

    private fun ui(linked: Boolean) = ModeSheetUi(
        profile = null,
        controlMode = ControlMode.Ados,
        avoid = AvoidMode.Brake,
        owner = PilotOwner.Pilot,
        vehicleClass = null,
        elrsAvailable = false,
        pilotLinked = linked,
        fcModes = ModePickerUi(
            listOf(ModeChoice("LOITER", current = true, gesture = ConfirmGesture.Tap), ModeChoice("RTL", current = false, gesture = ConfirmGesture.Tap)),
            enabled = true,
            reasonKey = null,
        ),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun show(linked: Boolean, keyboard: Boolean = false) = compose.setContent {
        if (keyboard) LocalInputModeManager.current.requestInputMode(InputMode.Keyboard)
        AdosTheme {
            // The app wraps everything in this guard; it is what drops a cancelled release.
            CancelledConfirmGuard {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    ModeSheet(ui(linked), testSpec.flightState.modeSheet, actions, testSpec.layout.holdsMs)
                }
            }
        }
    }

    private fun centre(action: Int, flags: Int = 0) =
        KeyEvent(NativeKeyEvent(0L, 0L, action, NativeKeyEvent.KEYCODE_DPAD_CENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags))

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a cancelled Cross release on an FC mode tile asks for nothing`() {
        show(linked = true, keyboard = true)
        compose.onNodeWithTag(TAG_FC_MODES).performScrollTo().performClick()
        val tile = compose.onNodeWithText("RTL").performScrollTo()
        tile.requestFocus()
        // Cross down on the tile, then a chord takes Cross over: the bridge ends the press cancelled.
        tile.performKeyPress(centre(NativeKeyEvent.ACTION_DOWN))
        tile.performKeyPress(centre(NativeKeyEvent.ACTION_UP, NativeKeyEvent.FLAG_CANCELED))
        compose.waitForIdle()
        assertEquals(emptyList<String>(), picked)
        // A plain release on the same tile still asks for its mode.
        tile.performKeyPress(centre(NativeKeyEvent.ACTION_DOWN))
        tile.performKeyPress(centre(NativeKeyEvent.ACTION_UP))
        compose.waitForIdle()
        assertEquals(listOf("RTL"), picked)
    }

    @Test
    fun `an FC mode picked in the advanced section is asked for`() {
        show(linked = true)
        compose.onNodeWithText(testSpec.setupStrings.format("flow.mode.section.fc_modes", emptyMap())).performScrollTo()
        compose.onNodeWithTag(TAG_FC_MODES).performScrollTo().performClick()
        compose.onNodeWithText("RTL").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("RTL"), picked)
    }

    @Test
    fun `the FC modes stay reachable without a pilot link`() {
        show(linked = false)
        compose.onNodeWithTag(TAG_FC_MODES).performScrollTo().performClick()
        compose.onNodeWithText("RTL").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("RTL"), picked)
    }
}
