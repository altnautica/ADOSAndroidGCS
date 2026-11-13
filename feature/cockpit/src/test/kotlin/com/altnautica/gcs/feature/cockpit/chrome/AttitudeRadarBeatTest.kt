package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.model.Attitude
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The attitude radar follows a 60 Hz attitude stream at most once a beat. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttitudeRadarBeatTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an attitude sample every frame redraws the radar at most once a beat`() {
        val attitude = MutableStateFlow<Attitude?>(Attitude(0f, 0f, 0f, 0L))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Box(Modifier.size(160.dp).testTag("radar")) { AttitudeRadar(attitude) }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        var previous = compose.onNodeWithTag("radar").captureToImage().toPixelMap().buffer.toList()
        var changes = 0
        val startMs = compose.mainClock.currentTime
        repeat(FRAMES) { i ->
            attitude.value = Attitude(rollRad = (i + 1) * 0.01f, pitchRad = 0.05f, yawRad = 0f, tMs = i.toLong())
            compose.mainClock.advanceTimeByFrame()
            val frame = compose.onNodeWithTag("radar").captureToImage().toPixelMap().buffer.toList()
            if (frame != previous) changes++
            previous = frame
        }
        val elapsedMs = compose.mainClock.currentTime - startMs
        assertTrue("the radar still follows the attitude ($changes changes)", changes >= 5)
        assertTrue("$changes changes in $elapsedMs ms", changes <= elapsedMs / RedrawBeat.BEAT_MS + 1)
    }

    private companion object {
        /** About one second of 16 ms frames. */
        const val FRAMES = 60
    }
}
