package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.altnautica.gcs.core.model.PositionFix
import androidx.compose.runtime.CompositionLocalProvider
import com.altnautica.gcs.feature.cockpit.FakeTelemetry
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The centre is clean below the Instruments level: no tapes, no ladder; the reticle always. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w800dp-h400dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudLevelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `tapes and ladder draw only with the instruments on`() {
        val telemetry = FakeTelemetry().apply {
            attitude.value = Attitude(rollRad = 0.1f, pitchRad = 0.05f, yawRad = 1f, tMs = 1_000L)
            position.value = PositionFix(47.0, 8.0, 420f, 30f, 3f, 0f, -0.5f, 1_000L)
        }
        var instruments by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) { AdosTheme {
                Box(Modifier.size(800.dp, 400.dp).background(Color.Black).testTag("stage")) {
                    HudCanvas(
                        telemetry = telemetry,
                        altitudeReference = AltitudeReference.Rel,
                        density = CockpitDensity.Std,
                        instruments = instruments,
                        modifier = Modifier.size(800.dp, 400.dp),
                        nowMs = { 1_000L },
                    )
                }
            } }
        }
        val clean = lit()
        instruments = true
        compose.waitForIdle()
        val full = lit()
        // The speed tape (left edge) and altitude tape (right edge) regions.
        assertEquals("no speed tape below Instruments", 0, clean.left)
        assertEquals("no altitude tape below Instruments", 0, clean.right)
        assertTrue("the reticle is always drawn", clean.centre > 0)
        assertTrue("Instruments draws the speed tape", full.left > 50)
        assertTrue("Instruments draws the altitude tape", full.right > 50)
    }

    private data class Lit(val left: Int, val right: Int, val centre: Int)

    private fun lit(): Lit {
        val map = compose.onNodeWithTag("stage").captureToImage().toPixelMap()
        fun count(x0: Int, x1: Int, y0: Int, y1: Int): Int {
            var n = 0
            for (x in x0 until x1) for (y in y0 until y1) {
                val c = map[x, y]
                if (c.red + c.green + c.blue > 0.3f) n++
            }
            return n
        }
        val w = map.width
        val h = map.height
        return Lit(
            left = count((w * 0.015f).toInt(), (w * 0.09f).toInt(), (h * 0.3f).toInt(), (h * 0.7f).toInt()),
            right = count((w * 0.88f).toInt(), (w * 0.97f).toInt(), (h * 0.3f).toInt(), (h * 0.7f).toInt()),
            centre = count(w / 2 - 20, w / 2 + 20, h / 2 - 4, h / 2 + 4),
        )
    }
}
