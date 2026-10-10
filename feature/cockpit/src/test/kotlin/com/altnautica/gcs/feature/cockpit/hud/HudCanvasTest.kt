package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.feature.cockpit.FakeTelemetry
import com.altnautica.gcs.feature.cockpit.settings.AltitudeReference
import com.altnautica.gcs.feature.cockpit.settings.CockpitDensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudCanvasTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `telemetry updates redraw the HUD without recomposing it`() {
        val telemetry = FakeTelemetry()
        var recompositions = 0
        var draws = 0
        var invalidations = 0
        // The HUD keeps a 500 ms stale-redraw ticker; drive frames by hand so idling never waits on it.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AdosTheme {
                HudCanvas(
                    telemetry = telemetry,
                    altitudeReference = AltitudeReference.Rel,
                    density = CockpitDensity.Std,
                    nowMs = { 1_000L },
                    recompositionProbe = { recompositions++ },
                    drawProbe = { draws++ },
                    invalidationProbe = { invalidations++ },
                )
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val composedOnce = recompositions
        val invalidationsBefore = invalidations
        repeat(20) { i ->
            telemetry.attitude.value = Attitude(rollRad = i * 0.01f, pitchRad = 0.02f, yawRad = 0.5f, tMs = 1_000L)
            telemetry.position.value = PositionFix(47.0, 8.0, 420f, i.toFloat(), 3f, 0f, -0.5f, 1_000L)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertEquals("no recomposition per telemetry tick", composedOnce, recompositions)
        assertTrue("new samples invalidate the draw pass", invalidations - invalidationsBefore >= 10)
        // Rendering the frame runs the draw pass, still without recomposing.
        compose.onRoot().captureToImage()
        assertTrue("the draw pass ran", draws > 0)
        assertEquals(composedOnce, recompositions)
    }

    @Test
    fun `ladder rungs every 5 degrees around the current pitch`() {
        val rungs = HudMath.ladderRungs(0f)
        assertEquals(listOf(-25, -20, -15, -10, -5, 5, 10, 15, 20, 25), rungs)
        assertTrue(HudMath.ladderRungs(88f).all { it <= 90 })
    }

    @Test
    fun `flight path marker shows climb angle and drift`() {
        // Flying north at 10 m/s, climbing at 10 m/s, nose level, heading north.
        val fpm = HudMath.flightPathOffsetDeg(vx = 10f, vy = 0f, vz = -10f, pitchDeg = 0f, headingDeg = 0f)
        assertNotNull(fpm)
        assertEquals(0f, fpm!!.first, 0.01f)
        assertEquals(45f, fpm.second, 0.01f)
        // Tracking east with the nose north: 90 degrees of drift to the right.
        assertEquals(90f, HudMath.flightPathOffsetDeg(0f, 10f, 0f, 0f, 0f)!!.first, 0.01f)
        assertNull(HudMath.flightPathOffsetDeg(0.2f, 0.1f, 0f, 0f, 0f))
    }

    @Test
    fun `instruments older than 2 s are stale`() {
        assertTrue(HudMath.isFresh(0, 2_000))
        assertTrue(!HudMath.isFresh(0, 2_001))
        assertEquals("N", HudMath.headingLabel(360))
        assertEquals("W", HudMath.headingLabel(-90))
        assertEquals(-170f, HudMath.wrap180(190f), 0.001f)
    }
}
