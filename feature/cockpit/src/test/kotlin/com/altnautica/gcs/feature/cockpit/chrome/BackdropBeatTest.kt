package com.altnautica.gcs.feature.cockpit.chrome

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A new copy of the video behind the panels redraws them in a beat's frame, never in one of its own. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackdropBeatTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a new backdrop copy reaches the panels on the next beat`() {
        val backdrop = ChromeBackdrop()
        val beat = RedrawBeat()
        val beats = ArrayList<Long>()
        var sampling by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val context = LocalContext.current
            LaunchedEffect(beat) {
                backdrop.view = View(context).apply { layout(0, 0, 240, 120) }
                beat.run()
            }
            LaunchedEffect(beat) { beat.ticks.collect { beats += compose.mainClock.currentTime } }
            CompositionLocalProvider(LocalRedrawBeat provides beat) {
                // The copy always succeeds; what matters is when it is shown.
                if (sampling) BackdropSampler(backdrop) { _, _ -> true }
            }
        }
        // Start sampling half a beat after the beat started, so the copy's own clock is off the beat.
        compose.mainClock.advanceTimeBy(48)
        sampling = true
        var shownAt: Long? = null
        while (shownAt == null && compose.mainClock.currentTime < 3_000) {
            compose.mainClock.advanceTimeBy(4)
            if (backdrop.frame != null) shownAt = compose.mainClock.currentTime
        }
        assertNotNull("the backdrop was copied", shownAt)
        val lastBeat = beats.last { it <= shownAt!! }
        assertTrue("the copy was shown at $shownAt ms, ${shownAt!! - lastBeat} ms after the beat at $lastBeat", shownAt - lastBeat <= 8)
    }
}
