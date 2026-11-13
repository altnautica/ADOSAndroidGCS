package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.FakeTelemetry
import com.altnautica.gcs.feature.cockpit.autonomy.NavState
import com.altnautica.gcs.feature.cockpit.autonomy.ScanFrame
import com.altnautica.gcs.feature.cockpit.autonomy.Stamped
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The proximity radar under a scan and nav stream at the frame rate: the dial redraws at
 * most once a beat, and neither its draw pass nor the nav stream recomposes it while what
 * the composition shows (stale, scale, the rounded rate) holds.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProximityRadarBeatTest {
    @get:Rule
    val compose = createComposeRule()

    /** The spec strings, counting each key's lookups: the radar reads its label once per composition. */
    private class CountingStrings(private val base: Map<String, String>) : Map<String, String> by base {
        private val reads = HashMap<String, Int>()
        fun reads(key: String): Int = reads[key] ?: 0
        override fun get(key: String): String? {
            reads[key] = reads(key) + 1
            return base[key]
        }
    }

    private val now = 10_000L

    private fun scan(i: Int) = ScanFrame(0f, 0.1f, FloatArray(60) { k -> 2.5f + ((k + i) % 10) * 0.1f }, receivedAtMs = now)

    private fun nav(i: Int) = Stamped(NavState("guided", 1, null, 30f, 20.2f, 2.5f, 3), atMs = now - 50 + i % 10)

    @Test
    fun `a frame-rate scan and nav stream redraws the dial at most once a beat without recomposing it`() {
        val strings = CountingStrings(testSpec.setupStrings.strings)
        val scanFlow = MutableStateFlow<ScanFrame?>(scan(0))
        val navFlow = MutableStateFlow<Stamped<NavState>?>(nav(0))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings.copy(strings = strings)) {
                AdosTheme {
                    Box(Modifier.size(160.dp).testTag("radar")) {
                        ProximityRadar(FakeTelemetry(), scanFlow, navFlow, nowMs = { now }, diameter = 120.dp)
                    }
                }
            }
        }
        // Settle: the first beat samples the dial and sets the scale it shows.
        repeat(SETTLE_FRAMES) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()
        val composed = strings.reads(ARIA)
        assertTrue("the radar composed", composed > 0)

        var previous = compose.onNodeWithTag("radar").captureToImage().toPixelMap().buffer.toList()
        var changes = 0
        val startMs = compose.mainClock.currentTime
        repeat(FRAMES) { i ->
            scanFlow.value = scan(i + 1)
            navFlow.value = nav(i + 1)
            compose.mainClock.advanceTimeByFrame()
            val frame = compose.onNodeWithTag("radar").captureToImage().toPixelMap().buffer.toList()
            if (frame != previous) changes++
            previous = frame
        }
        val elapsedMs = compose.mainClock.currentTime - startMs
        assertTrue("the dial still follows the scan ($changes changes)", changes >= 5)
        assertTrue("$changes changes in $elapsedMs ms", changes <= elapsedMs / RedrawBeat.BEAT_MS + 1)
        assertEquals("the streams never recompose the radar", composed, strings.reads(ARIA))
    }

    private companion object {
        const val ARIA = "cockpit.radar.aria"
        const val SETTLE_FRAMES = 20

        /** About one second of 16 ms frames. */
        const val FRAMES = 60
    }
}
