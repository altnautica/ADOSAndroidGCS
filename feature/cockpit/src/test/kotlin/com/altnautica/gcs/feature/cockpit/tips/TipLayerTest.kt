package com.altnautica.gcs.feature.cockpit.tips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.core.skills.tips.TipsRepository
import com.altnautica.gcs.feature.cockpit.MemoryPreferences
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp")
class TipLayerTest {
    @get:Rule
    val compose = createComposeRule()

    private val engine = TipsEngine(testSpec.tips)
    private val repository = TipsRepository(MemoryPreferences())
    private val handle = TipsHandle()
    private var cockpitTaps = 0
    private val ground = TipFacts(
        armed = false,
        airborne = false,
        sheetOrMenuOpen = false,
        tourVisible = false,
        skillsAvailable = false,
        gotoOffered = false,
        gimbalAvailable = false,
    )

    private fun showCockpit() {
        // The layer ticks every 250 ms; drive the clock by hand so idling never waits on it.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AdosTheme {
                CompositionLocalProvider(
                    LocalCockpitStrings provides testSpec.setupStrings,
                    LocalTipAnchors provides TipAnchorRegistry(),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.align(Alignment.Center).size(120.dp, 40.dp).tipAnchor("status_pill"))
                        // STOP sits where the tip would go when centred beside the anchor.
                        Box(Modifier.offset(60.dp, 170.dp).size(80.dp).tipAnchor("stop"))
                        Box(
                            Modifier.align(Alignment.BottomEnd).size(60.dp).testTag("cockpit-control").clickable { cockpitTaps++ },
                        )
                        TipLayer(ground, repository, engine, handle = handle, nowMs = { compose.mainClock.currentTime })
                    }
                }
            }
        }
        frame()
    }

    private fun frame() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun advance(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    private fun seen(): List<String> = runBlocking { repository.state.first().seen }

    @Test
    fun `the tip shows after the first delay, clear of STOP, and Got it marks it seen`() {
        showCockpit()
        advance(2_000)
        compose.onNodeWithTag(TAG_TIP).assertDoesNotExist()
        advance(700)
        compose.onNodeWithTag(TAG_TIP).assertIsDisplayed()
        val tip = compose.onNodeWithTag(TAG_TIP).getUnclippedBoundsInRoot()
        val stop = compose.onNodeWithTag("tip-anchor-stop").getUnclippedBoundsInRoot()
        assertFalse("the tip covers STOP", overlaps(tip, stop))
        assertEquals(1, runBlocking { repository.state.first().openCount })

        compose.onNodeWithTag(TAG_TIP_DISMISS).performClick()
        frame()
        compose.onNodeWithTag(TAG_TIP).assertDoesNotExist()
        assertEquals(listOf("status_pill"), seen())
    }

    @Test
    fun `touches outside the tip reach the cockpit and leave the tip up`() {
        showCockpit()
        advance(2_700)
        compose.onNodeWithTag(TAG_TIP).assertIsDisplayed()
        compose.onNodeWithTag("cockpit-control").performClick()
        frame()
        assertEquals(1, cockpitTaps)
        compose.onNodeWithTag(TAG_TIP).assertIsDisplayed()
        assertTrue(seen().isEmpty())
    }

    @Test
    fun `a touch on the anchor dismisses the tip for good`() {
        showCockpit()
        advance(2_700)
        compose.onNodeWithTag("tip-anchor-status_pill").performClick()
        frame()
        compose.onNodeWithTag(TAG_TIP).assertDoesNotExist()
        assertEquals(listOf("status_pill"), seen())
    }

    @Test
    fun `a controller button dismisses the tip for good`() {
        showCockpit()
        advance(2_700)
        assertTrue(handle.isShowing)
        assertTrue(handle.dismissFromControllerButton())
        frame()
        compose.onNodeWithTag(TAG_TIP).assertDoesNotExist()
        assertEquals(listOf("status_pill"), seen())
        assertFalse(handle.dismissFromControllerButton())
    }

    private fun overlaps(a: DpRect, b: DpRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
