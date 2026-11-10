package com.altnautica.gcs.feature.cockpit.modes

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    private fun show(linked: Boolean) = compose.setContent {
        AdosTheme {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                ModeSheet(ui(linked), testSpec.flightState.modeSheet, actions, testSpec.layout.holdsMs)
            }
        }
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
