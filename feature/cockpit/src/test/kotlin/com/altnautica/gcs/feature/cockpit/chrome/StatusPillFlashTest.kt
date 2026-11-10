package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The status pill on screen: a new item flashes it until the list is opened, opening the
 * list acknowledges what it shows, and the flash runs in the draw phase only, so a
 * flashing pill is never recomposed frame by frame.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusPillFlashTest {
    @get:Rule
    val compose = createComposeRule()

    /** The spec strings, counting each key's lookups: the pill reads its click label once per composition. */
    private class CountingStrings(private val base: Map<String, String>) : Map<String, String> by base {
        private val reads = HashMap<String, Int>()
        fun reads(key: String): Int = reads[key] ?: 0
        override fun get(key: String): String? {
            reads[key] = reads(key) + 1
            return base[key]
        }
    }

    private val strings = CountingStrings(testSpec.setupStrings.strings)

    /** One composition of the pill reads its click label once; nothing else in the pill reads it. */
    private fun pillCompositions() = strings.reads(PILL_LABEL)

    private val lowBattery = StatusItem("text:Battery low", StatusSource.FcStatusText, StatusSeverity.Warning, text = "Battery low")
    private val gpsGlitch = StatusItem("text:GPS glitch", StatusSource.FcStatusText, StatusSeverity.Warning, text = "GPS glitch")

    /** The flight screen's pill wiring: opening the list acknowledges every item it shows. */
    @Composable
    private fun PillAndList(items: List<StatusItem>) {
        var acknowledged by remember { mutableStateOf(emptySet<String>()) }
        var open by remember { mutableStateOf(false) }
        Box(Modifier.size(480.dp, 320.dp)) {
            StatusPill(
                StatusModel.pill(items, acknowledged),
                onOpen = {
                    acknowledged = items.mapTo(HashSet()) { it.key }
                    open = true
                },
                modifier = Modifier.align(Alignment.BottomStart),
            )
            if (open) StatusList(items, onClose = { open = false })
        }
    }

    /** The pill's pixels sampled across one full flash period. */
    private fun pillFramesOverOnePeriod(): Set<List<Int>> = (0 until SAMPLES).mapTo(HashSet()) { i ->
        if (i > 0) compose.mainClock.advanceTimeBy(SAMPLE_STEP_MS)
        compose.onNodeWithTag("status-pill").captureToImage().toPixelMap().buffer.toList()
    }

    /**
     * Renders one frame after a state change: the write is applied first (the paused
     * clock never applies it on its own), then the frame recomposes and draws.
     */
    private fun nextFrame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a new alert flashes the pill without recomposing it until opening the list acknowledges it`() {
        var items by mutableStateOf(emptyList<StatusItem>())
        // Infinite animations run only on a clock the test drives.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings.copy(strings = strings)) {
                PillAndList(items)
            }
        }
        nextFrame()
        compose.onNodeWithText("All systems OK").assertExists()
        assertEquals("nothing active holds steady", 1, pillFramesOverOnePeriod().size)

        // A new alert: the pill recomposes once to show it, then flashes.
        val before = pillCompositions()
        items = listOf(lowBattery)
        nextFrame()
        compose.onNodeWithText("Battery low").assertExists()
        assertTrue("the counter sees the pill compose", pillCompositions() > before)
        // The transition's own start-up (running flag) settles within its first frames.
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        val composed = pillCompositions()
        assertTrue("an unacknowledged alert flashes", pillFramesOverOnePeriod().size > 1)
        assertEquals("the flash is drawn, never recomposed", composed, pillCompositions())

        // Opening the list acknowledges the alert: the list shows and the pill holds steady.
        compose.onNodeWithTag("status-pill").performClick()
        nextFrame()
        compose.onNodeWithTag("status-list", useUnmergedTree = true).assertExists()
        assertEquals("an acknowledged alert holds steady", 1, pillFramesOverOnePeriod().size)

        // A tap outside the list (over the pill, under the list's scrim) closes it; a later alert flashes the pill again.
        compose.onNodeWithTag("status-pill").performClick()
        nextFrame()
        compose.onNodeWithTag("status-list", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("closing the list keeps the acknowledgement", 1, pillFramesOverOnePeriod().size)
        items = listOf(lowBattery, gpsGlitch)
        nextFrame()
        compose.onNodeWithText("2 warnings").assertExists()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        val recomposed = pillCompositions()
        assertTrue("a newer alert flashes again", pillFramesOverOnePeriod().size > 1)
        assertEquals("the flash is drawn, never recomposed", recomposed, pillCompositions())
    }

    private companion object {
        const val PILL_LABEL = "cockpit.alerts.title"

        /** Seven samples 150 ms apart span the 900 ms flash period. */
        const val SAMPLES = 7
        const val SAMPLE_STEP_MS = 150L

        /** Long enough for an infinite transition to start; a pill recomposed per frame would recompose 50+ times in the period after it. */
        const val SETTLE_MS = 100L
    }
}
