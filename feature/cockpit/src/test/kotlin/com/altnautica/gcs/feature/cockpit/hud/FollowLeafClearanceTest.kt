package com.altnautica.gcs.feature.cockpit.hud

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.OverheadAhead
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The range panel's leaf-clearance lines: the follow height lowered under leaves (with why)
 * and the overhead clearance the pilot reports ahead, each only while it applies.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FollowLeafClearanceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val strings = testSpec.setupStrings

    private val follow = FollowState(
        phase = FollowPhase.Following, trackId = 12, confidence = 0.8f, rangeM = 10.2f, rangeSigmaM = 0.3f,
        rangeCues = emptySet(), targetSpeedMps = 1.2f, aglM = 4f, aglSource = "rng", reacquireMode = null,
        reacquireRemainingS = null, pipelineLatencyMs = null, holdReason = null, bearingDeg = 10f, depressionDeg = 20f,
        targetNed = null, targetVelNed = null, predicted = null, heightSetpointM = 6f,
    )
    private val under = follow.copy(heightSetpointM = 3.87f, heightLowered = true, heightReason = "canopy")
    private val overhead = OverheadAhead(7L, 3.87f, 2.5f)

    private fun show(f: FollowState, width: WidthClass, stale: Boolean = false, ahead: OverheadAhead? = null) {
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides strings) {
                    FollowRangePanel(f, stale = stale, width = width, overhead = ahead)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the words name the lowered height with why and the overhead clearance ahead`() {
        assertEquals("Flying under leaves: 3.9 m" to "Lowered under the leaves ahead", FollowWords.heightLine(under, strings))
        assertEquals(
            "At the lowest follow height, leaves lower still",
            FollowWords.heightLine(under.copy(heightReason = "canopy_floor"), strings)?.second,
        )
        assertEquals("Flying under leaves: 3.9 m" to null, FollowWords.heightLine(under.copy(heightReason = null), strings))
        assertNull(FollowWords.heightLine(follow, strings))
        assertEquals("Overhead clearance ahead: 3.9 m in 3 m", FollowWords.overheadLine(overhead, strings))
        assertNull(FollowWords.overheadLine(null, strings))
    }

    @Test
    fun `the full panel shows both lines while they apply`() {
        show(under, WidthClass.Expanded, ahead = overhead)
        compose.onNodeWithText("Flying under leaves: 3.9 m").assertIsDisplayed()
        compose.onNodeWithText("Lowered under the leaves ahead").assertIsDisplayed()
        compose.onNodeWithText("Overhead clearance ahead: 3.9 m in 3 m").assertIsDisplayed()
    }

    @Test
    fun `the strip line carries the lowered height`() {
        show(under, WidthClass.Compact)
        compose.onNodeWithText("Flying under leaves: 3.9 m").assertIsDisplayed()
    }

    @Test
    fun `a stale report shows no lowered height`() {
        show(under, WidthClass.Expanded, stale = true)
        assertEquals(0, compose.onAllNodesWithText("Flying under leaves", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun `a height flown as set and no overhead report show neither line`() {
        show(follow, WidthClass.Expanded)
        assertEquals(0, compose.onAllNodesWithText("Flying under leaves", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Overhead clearance", substring = true).fetchSemanticsNodes().size)
    }
}
