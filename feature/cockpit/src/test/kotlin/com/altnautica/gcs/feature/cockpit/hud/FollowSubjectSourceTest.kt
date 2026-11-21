package com.altnautica.gcs.feature.cockpit.hud

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.autonomy.AutonomyParse
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.NavFrame
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The range panel names the subject source while the follow flies on the phone's position or a prediction. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FollowSubjectSourceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val strings = testSpec.setupStrings

    private val follow = FollowState(
        phase = FollowPhase.Following, trackId = 12, confidence = 0.8f, rangeM = 10.2f, rangeSigmaM = 0.3f,
        rangeCues = emptySet(), targetSpeedMps = 1.2f, aglM = 4f, aglSource = "rng", reacquireMode = null,
        reacquireRemainingS = null, pipelineLatencyMs = null, holdReason = null, bearingDeg = 10f, depressionDeg = 20f,
        targetNed = null, targetVelNed = null, predicted = null,
    )

    private fun show(f: FollowState, width: WidthClass, stale: Boolean = false) {
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides strings) {
                    FollowRangePanel(f, stale = stale, width = width)
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the follow state parses the source and the words name beacon and predicted only`() {
        fun parsed(src: String) =
            (AutonomyParse.navFrame("""{"kind":"follow","ts_ms":1,"data":{"phase":"following","subject_source":"$src"}}""", 0) as NavFrame.Follow).follow
        assertEquals("beacon", parsed("beacon").subjectSource)
        assertNull(parsed("radar").subjectSource)
        assertEquals("Following on phone position", FollowWords.sourceLine(follow.copy(subjectSource = "beacon"), strings))
        assertEquals("Predicted", FollowWords.sourceLine(follow.copy(subjectSource = "predicted"), strings))
        assertNull(FollowWords.sourceLine(follow.copy(subjectSource = "vision"), strings))
        assertNull(FollowWords.sourceLine(follow, strings))
    }

    @Test
    fun `the full panel names a beacon source`() {
        show(follow.copy(subjectSource = "beacon"), WidthClass.Expanded)
        compose.onNodeWithText("Following on phone position").assertIsDisplayed()
    }

    @Test
    fun `the strip line names a predicted source`() {
        show(follow.copy(subjectSource = "predicted"), WidthClass.Compact)
        compose.onNodeWithText("Predicted").assertIsDisplayed()
    }

    @Test
    fun `a stale report shows no source line`() {
        show(follow.copy(subjectSource = "beacon"), WidthClass.Expanded, stale = true)
        assertEquals(0, compose.onAllNodesWithText("Following on phone position").fetchSemanticsNodes().size)
    }

    @Test
    fun `a vision source shows no source line`() {
        show(follow.copy(subjectSource = "vision"), WidthClass.Expanded)
        assertEquals(0, compose.onAllNodesWithText("Following on phone position").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("Predicted").fetchSemanticsNodes().size)
    }
}
