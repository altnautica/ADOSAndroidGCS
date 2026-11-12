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
import com.altnautica.gcs.core.skills.builtin.FlyHereSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.feature.cockpit.flatDistanceM
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Fly Here confirm shows what will be flown: the point, its distance, the height and that it takes over. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FlyHereConfirmSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val strings = testSpec.setupStrings

    private val actions = object : ConfirmActions {
        override fun onTouchHold(pressed: Boolean) = Unit
        override fun onSlideCompleted() = Unit
        override fun setAltitude(meters: Float) = Unit
        override fun setOverride(on: Boolean) = Unit
        override fun cancel() = Unit
    }

    private fun show(args: Map<String, Any?>) {
        val state = ConfirmUiState(
            request = ConfirmRequest(DeviceId("d"), FlyHereSkill, FlyHereSkill.confirm!!, args, gamepadButton = null, checklistReady = true),
            altitudeM = null,
            failingChecks = emptyList(),
            overrideChecklist = false,
            progress = 0f,
            guardRemainingMs = null,
        )
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides strings) { ConfirmHost(state, actions) }
            }
        }
    }

    @Test
    fun `the sheet lists the point, distance and height, says it takes over, and holds to fly here`() {
        show(mapOf(SkillArgs.LAT to 47.3976541, SkillArgs.LON to 8.5455938, SkillArgs.ALTITUDE_M to 24.6f, ARG_FLY_HERE_DISTANCE_M to 132.4f))
        compose.onNodeWithText(strings.format("map.fly_here.point")).assertExists()
        compose.onNodeWithText("47.397654, 8.545594").assertExists()
        compose.onNodeWithText(strings.format("map.fly_here.distance_value", mapOf("dist" to "132"))).assertExists()
        compose.onNodeWithText(strings.format("map.fly_here.height_value", mapOf("alt" to "25"))).assertExists()
        compose.onNodeWithText(strings.format("map.fly_here.takes_over")).assertExists()
        compose.onNodeWithText(strings.format("map.fly_here.hold")).assertExists()
        compose.onAllNodesWithText(strings.format("confirm.hold", mapOf("s" to "0.8"))).assertCountEquals(0)
    }

    @Test
    fun `an unknown distance reads as a dash`() {
        show(mapOf(SkillArgs.LAT to 47.0, SkillArgs.LON to 8.0, SkillArgs.ALTITUDE_M to 10f))
        compose.onNodeWithText(strings.format("map.fly_here.distance")).assertExists()
        compose.onAllNodesWithText("—").assertCountEquals(1)
    }

    @Test
    fun `the distance is ground metres between the drone and the point`() {
        // 0.001° of latitude is about 111 m.
        assertEquals(111.2f, flatDistanceM(47.001, 8.0, 47.0, 8.0), 0.5f)
    }
}
