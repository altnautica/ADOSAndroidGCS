package com.altnautica.gcs.feature.cockpit.hud

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The follow range panel carries no AGL: the bottom row holds the one AGL on screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FollowRangeNoAglTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val follow = FollowState(
        phase = FollowPhase.Following, trackId = 12, confidence = 0.8f, rangeM = 10.2f, rangeSigmaM = 0.3f,
        rangeCues = emptySet(), targetSpeedMps = 1.2f, aglM = 15f, aglSource = "rng", reacquireMode = null,
        coastRemainingS = null, pipelineLatencyMs = null, holdReason = null, bearingDeg = 10f, depressionDeg = 20f,
        targetNed = null, targetVelNed = null, predicted = null,
    )

    private fun show(width: WidthClass) {
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                    FollowRangePanel(follow, stale = false, width = width)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun compactShowsRangeWithoutAgl() {
        show(WidthClass.Compact)
        compose.onNodeWithText("10.2 m ±0.6").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText("AGL", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun expandedShowsNoAglRow() {
        show(WidthClass.Expanded)
        assertEquals(0, compose.onAllNodesWithText("AGL", substring = true).fetchSemanticsNodes().size)
    }
}
