package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosColors
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.core.model.WindState
import com.altnautica.gcs.feature.cockpit.FakeTelemetry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * The HUD redraws ten times a second for a whole flight, so a draw with unchanged telemetry
 * must not allocate: every readout's layout, the dash effect, the strokes and the paths are
 * kept across draws.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HudPainterAllocTest {
    private val now = 5_000L

    private fun telemetry() = FakeTelemetry().apply {
        // Nose down 10 degrees so negative (dashed) ladder rungs draw, moving so the flight-path marker draws.
        attitude.value = Attitude(rollRad = 0.1f, pitchRad = -0.17f, yawRad = 1.2f, tMs = now)
        position.value = PositionFix(47.0, 8.0, 512.3f, 42.7f, 6f, 2f, -0.5f, now)
        vfr.value = Vfr(airspeedMs = 12.4f, groundspeedMs = 11.8f, headingDeg = 68.6f, throttlePct = 55, climbMs = 0.6f, tMs = now)
        wind.value = WindState(directionDeg = 250f, speedMs = 4.2f, tMs = now)
        home.value = HomePosition(47.001, 8.002, 470f, now)
        heartbeat.value = HeartbeatState(armed = true, mode = FlightMode(5, "LOITER"), vehicleType = 2, autopilot = 3, tMs = now)
    }

    /** Bytes the test thread allocates per draw over [DRAWS] warm draws of [instruments]. */
    private fun bytesPerDraw(instruments: Boolean): Long {
        val density = Density(2f)
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr, TEXT_CACHE_SIZE)
        val painter = HudPainter(measurer, AdosColors.BrandDark, AdosTypography.numeric.small, AdosTypography.numeric.medium)
        val t = telemetry()
        val canvas = Canvas(ImageBitmap(800, 480))
        val scope = CanvasDrawScope()
        fun drawOnce() = scope.draw(density, LayoutDirection.Ltr, canvas, Size(800f, 480f)) {
            with(painter) { paint(t, AltitudeReference.Rel, CockpitDensity.Std, now, instruments) }
        }
        repeat(WARM_UP) { drawOnce() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = bean.currentThreadAllocatedBytes
        repeat(DRAWS) { drawOnce() }
        return (bean.currentThreadAllocatedBytes - before) / DRAWS
    }

    @Test
    fun `a steady instruments draw allocates no more than the canvas itself does`() {
        val perDraw = bytesPerDraw(instruments = true)
        assertTrue("steady HUD draw allocated $perDraw bytes per draw", perDraw < INSTRUMENTS_BOUND)
    }

    private companion object {
        const val WARM_UP = 20
        const val DRAWS = 50

        /**
         * The shadow canvas allocates on its own (about 1.4 to 2.5 KiB for the layer stack's
         * forty-odd calls, see LayerStackTest); the instruments make about a hundred calls. A draw
         * that re-measures its thirty labels (a few hundred bytes each through the text cache)
         * and formats three readouts (a Formatter each) allocates well over 16 KiB.
         */
        const val INSTRUMENTS_BOUND = 10_240L
    }
}
