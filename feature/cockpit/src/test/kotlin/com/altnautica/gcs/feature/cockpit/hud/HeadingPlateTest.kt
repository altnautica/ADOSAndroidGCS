package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.feature.cockpit.FakeTelemetry
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/** The heading readout above the reticle sits on a panel plate, so it reads over a white picture. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HeadingPlateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the heading degrees draw on a dark plate over a white picture`() {
        val telemetry = FakeTelemetry()
        telemetry.attitude.value = Attitude(rollRad = 0f, pitchRad = 0f, yawRad = 0.5f, tMs = 1_000L)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                AdosTheme {
                    Box(Modifier.size(400.dp, 300.dp).background(Color.White).testTag("stage")) {
                        HudCanvas(telemetry, AltitudeReference.Rel, CockpitDensity.Std, instruments = false, nowMs = { 1_000L })
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val image = compose.onNodeWithTag("stage").captureToImage().toPixelMap()
        val d = compose.density.density
        val cx = image.width / 2
        // Inside the readout's line: the label's bottom is 64 dp above the reticle.
        val y = (image.height / 2 - 70 * d).roundToInt()
        val span = (30 * d).roundToInt()
        val dark = (cx - span..cx + span).count { x ->
            val p = image[x, y]
            p.red < 0.3f && p.green < 0.3f && p.blue < 0.3f
        }
        assertTrue("dark plate pixels on the readout's row: $dark", dark >= (12 * d).roundToInt())
    }
}
