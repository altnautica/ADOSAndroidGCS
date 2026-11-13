package com.altnautica.gcs.feature.cockpit.alerts

import android.os.SystemClock
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
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
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
 * A critical alert's border flashes on the flight screen's beat and in the draw pass: the
 * flash changes the picture at most once a beat and never recomposes the alert.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlertFlashTest {
    @get:Rule
    val compose = createComposeRule()

    /** The spec strings, counting each key's lookups: an alert row reads its level label once per composition. */
    private class CountingStrings(private val base: Map<String, String>) : Map<String, String> by base {
        private val reads = HashMap<String, Int>()
        fun reads(key: String): Int = reads[key] ?: 0
        override fun get(key: String): String? {
            reads[key] = reads(key) + 1
            return base[key]
        }
    }

    @Test
    fun `a flashing critical border changes at most once a beat and never recomposes`() {
        val strings = CountingStrings(testSpec.setupStrings.strings)
        // The robolectric clock holds still, so the alert stays inside its 3 s flash window.
        val alert = CockpitAlert("battery_critical", AlertLevel.Critical, "alert.battery_critical", null, SystemClock.elapsedRealtime())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings.copy(strings = strings)) {
                AdosTheme {
                    Box(Modifier.size(480.dp, 120.dp).testTag("alerts")) { AlertStack(listOf(alert)) }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val composed = strings.reads(LEVEL_LABEL)
        assertTrue("the row composed", composed > 0)

        var previous = compose.onNodeWithTag("alerts").captureToImage().toPixelMap().buffer.toList()
        var changes = 0
        val looks = HashSet<List<Int>>()
        val startMs = compose.mainClock.currentTime
        repeat(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            val frame = compose.onNodeWithTag("alerts").captureToImage().toPixelMap().buffer.toList()
            if (frame != previous) changes++
            looks += frame
            previous = frame
        }
        val elapsedMs = compose.mainClock.currentTime - startMs
        assertTrue("the border flashes between two looks (${looks.size})", looks.size >= 2)
        assertTrue("$changes changes in $elapsedMs ms", changes <= elapsedMs / RedrawBeat.BEAT_MS + 1)
        assertEquals("the flash is drawn, never recomposed", composed, strings.reads(LEVEL_LABEL))
    }

    private companion object {
        const val LEVEL_LABEL = "alert.level.critical"

        /** About one second of 16 ms frames. */
        const val FRAMES = 60
    }
}
