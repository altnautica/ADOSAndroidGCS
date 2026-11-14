package com.altnautica.gcs.feature.cockpit.hud

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPromptAction
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPrompts
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.Occluder
import com.altnautica.gcs.feature.cockpit.autonomy.SearchArea
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The follow chrome while the lock is lost: the chip word with the search time left, the range
 * panel's recovery line, the minimap's recovery geometry, and the waiting prompt with its actions.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FollowRecoveryChromeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val strings = testSpec.setupStrings

    private fun follow(phase: FollowPhase, remaining: Float? = null) = FollowState(
        phase = phase, trackId = 12, confidence = 0.8f, rangeM = 10.2f, rangeSigmaM = 0.3f,
        rangeCues = emptySet(), targetSpeedMps = 1.2f, aglM = 15f, aglSource = "rng", reacquireMode = "smart",
        reacquireRemainingS = remaining, pipelineLatencyMs = null, holdReason = null, bearingDeg = 10f, depressionDeg = 20f,
        targetNed = 2f to 1f, targetVelNed = null, predicted = null,
    )

    @Test
    fun `the chip word carries the whole seconds left while recovering and waits for the operator after`() {
        assertEquals("Predicting 19 s", FollowWords.lockWord(follow(FollowPhase.Predicting, 18.4f), strings))
        assertEquals("Moving to look 12 s", FollowWords.lockWord(follow(FollowPhase.Repositioning, 11.2f), strings))
        assertEquals("Searching 0 s", FollowWords.lockWord(follow(FollowPhase.Searching, -0.4f), strings))
        assertEquals("Searching", FollowWords.lockWord(follow(FollowPhase.Searching), strings))
        assertEquals("Waiting for you", FollowWords.lockWord(follow(FollowPhase.HoldingAwaitOperator), strings))
        assertEquals("Following", FollowWords.lockWord(follow(FollowPhase.Following), strings))
    }

    @Test
    fun `the recovery line names the time left, the vantage and what hides the subject`() {
        val searching = follow(FollowPhase.Searching, 11.2f).copy(vantageState = "arrived", occluder = Occluder(2f to 2f, "narrow"))
        assertEquals("12 s · Arrived · Behind a trunk or column", FollowWords.recoveryLine(searching, strings))
        assertEquals("On the way", FollowWords.recoveryLine(follow(FollowPhase.Repositioning).copy(vantageState = "enroute"), strings))
        assertNull(FollowWords.recoveryLine(follow(FollowPhase.Repositioning), strings))
        assertNull(FollowWords.recoveryLine(follow(FollowPhase.Following, 4f), strings))
    }

    @Test
    fun `the minimap dashes the target while recovering and hides it while idle or waiting`() {
        for (p in listOf(FollowPhase.Predicting, FollowPhase.Repositioning, FollowPhase.Searching)) assertTrue(p.name, MinimapFollow.recovering(follow(p)))
        for (p in listOf(FollowPhase.Acquiring, FollowPhase.Following, FollowPhase.Holding)) {
            assertTrue(p.name, MinimapFollow.shows(follow(p)))
            assertFalse(p.name, MinimapFollow.recovering(follow(p)))
        }
        assertFalse(MinimapFollow.shows(follow(FollowPhase.Idle)))
        assertFalse(MinimapFollow.shows(follow(FollowPhase.HoldingAwaitOperator)))
        assertFalse(MinimapFollow.shows(follow(FollowPhase.Searching).copy(targetNed = null)))
        // A north-pointing major axis turns the x radius up the screen.
        assertEquals(-90f, MinimapFollow.ellipseRotationDeg(SearchArea(0f to 0f, 3f, 1f, 0f).headingDeg))
    }

    @Test
    fun `the range panel shows the recovery line while searching`() {
        val searching = follow(FollowPhase.Searching, 11.2f).copy(vantageState = "arrived")
        compose.setContent {
            AdosTheme { CompositionLocalProvider(LocalCockpitStrings provides strings) { FollowRangePanel(searching, stale = false, width = WidthClass.Expanded) } }
        }
        compose.onNodeWithText("Searching").assertIsDisplayed()
        compose.onNodeWithText("12 s · Arrived").assertIsDisplayed()
    }

    @Test
    fun `the waiting prompt names why and runs the action tapped`() {
        val prompt = FollowPrompts.of(follow(FollowPhase.HoldingAwaitOperator).copy(holdReason = "search-exhausted"))!!
        val ran = mutableListOf<FollowPromptAction>()
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(LocalCockpitStrings provides strings) { FollowAwaitPrompt(prompt, WidthClass.Medium, onAction = { ran += it }) }
            }
        }
        compose.onNodeWithText("Target lost: holding, waiting for instructions").assertIsDisplayed()
        compose.onNodeWithText("The search found no match for the subject.").assertIsDisplayed()
        for (a in FollowPromptAction.entries) compose.onNodeWithTag("$FOLLOW_PROMPT_TAG-${a.wire}").assertIsDisplayed()
        compose.onNodeWithText("Search again").performClick()
        compose.onNodeWithText("Stop following").performClick()
        compose.onNodeWithText("Return home").performClick()
        assertEquals(listOf(FollowPromptAction.Resume, FollowPromptAction.Stop, FollowPromptAction.Rth), ran)
    }
}
