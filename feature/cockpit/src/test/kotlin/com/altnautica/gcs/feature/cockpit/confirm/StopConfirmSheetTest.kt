package com.altnautica.gcs.feature.cockpit.confirm

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.ConfirmRequest
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testBuiltin
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The emergency stop sheet: one title, one body and one hold button whose label counts down while held. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class StopConfirmSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val kill = testBuiltin("kill")
    private val strings = testSpec.setupStrings

    private val actions = object : ConfirmActions {
        override fun onTouchHold(pressed: Boolean) = Unit
        override fun onSlideCompleted() = Unit
        override fun setAltitude(meters: Float) = Unit
        override fun setOverride(on: Boolean) = Unit
        override fun cancel() = Unit
    }

    private fun show(holdRemainingMs: Long?) {
        val state = ConfirmUiState(
            request = ConfirmRequest(DeviceId("d"), kill, kill.confirm!!, emptyMap(), gamepadButton = null, checklistReady = true),
            altitudeM = null,
            failingChecks = emptyList(),
            overrideChecklist = false,
            progress = if (holdRemainingMs == null) 0f else 0.2f,
            guardRemainingMs = 2_000,
            holdRemainingMs = holdRemainingMs,
        )
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides strings) { ConfirmHost(state, actions) }
            }
        }
    }

    @Test
    fun `the stop sheet is titled Emergency stop with one hold button and no guard line`() {
        show(holdRemainingMs = null)
        compose.onNodeWithText(strings.format("cockpit.estop")).assertExists()
        compose.onNodeWithText(strings.format("confirm.kill.message")).assertExists()
        compose.onAllNodesWithText(strings.format("stop.hold_label")).assertCountEquals(1)
        compose.onAllNodesWithText("Guard armed", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(strings.format("confirm.kill.title")).assertCountEquals(0)
    }

    @Test
    fun `the hold button shows the time still needed while held`() {
        show(holdRemainingMs = 2_400)
        compose.onNodeWithText(strings.format("hold.keep_holding", mapOf("s" to "2.4"))).assertExists()
        compose.onAllNodesWithText(strings.format("stop.hold_label")).assertCountEquals(0)
    }
}
