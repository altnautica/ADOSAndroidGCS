package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.ui.geometry.Rect
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * Drawing the route between its last two frames must cost no more than drawing a single
 * frame: the interpolated points and positions are plain floats, never boxed per draw.
 * Measured as the difference between the two, so the canvas's own allocation cancels out.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerPainterAllocTest {
    private fun frame(shift: Float) = ArFrame(
        cameras = mapOf(
            "main" to ArCamera(
                items = List(LEGS) { leg ->
                    ArItem(
                        t = "leg", k = "leg$leg", role = if (leg == 0) "active" else "next",
                        pts = List(POINTS) { p -> listOf(p / POINTS.toFloat(), 0.1f + leg * 0.05f + shift) },
                    )
                } + List(LEGS) { wp ->
                    ArItem(t = "wp", k = "wp$wp", role = "next", x = 0.1f + wp * 0.05f + shift, y = 0.5f, gx = 0.5f, gy = 0.5f + shift)
                },
            ),
        ),
    )

    @Test
    fun `an interpolated route draw allocates no more than a single-frame draw`() {
        val density = Density(2f)
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)
        val flags = LayerFlags(LayerLevel.Full, setOf(LayerSpec.AR_ROUTE))
        val view = Rect(0f, 0f, 640f, 360f)
        val single = LayerSlot<ArFrame>().apply { push(frame(0f), stamp = 1, atMs = 0) }
        val moving = LayerSlot<ArFrame>().apply {
            push(frame(0f), stamp = 1, atMs = 0)
            push(frame(0.02f), stamp = 2, atMs = 100)
        }
        // Halfway between the two frames, so every point is eased.
        val now = 150L

        fun perDraw(slot: LayerSlot<ArFrame>): Long {
            val painter = LayerPainter(measurer)
            val canvas = Canvas(ImageBitmap(640, 360))
            val scope = CanvasDrawScope()
            fun drawOnce() = scope.draw(density, LayoutDirection.Ltr, canvas, Size(640f, 360f)) {
                with(painter) { drawAr(slot, "main", view, flags, RouteStyle.Normal, now, 1f) }
            }
            repeat(WARM_UP) { drawOnce() }
            val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val before = bean.currentThreadAllocatedBytes
            repeat(DRAWS) { drawOnce() }
            return (bean.currentThreadAllocatedBytes - before) / DRAWS
        }

        val still = perDraw(single)
        val eased = perDraw(moving)
        println("layer painter: eased $eased B, single-frame $still B per draw")
        assertTrue("eased draw $eased B against single-frame draw $still B", eased - still < BOUND)
    }

    private companion object {
        const val LEGS = 8
        const val POINTS = 40
        // Few draws, so the drawing code still runs as ART runs it: without the JIT's escape
        // analysis, which would otherwise remove short-lived boxes on the test JVM.
        const val WARM_UP = 2
        const val DRAWS = 5

        /**
         * Boxing the eased coordinates measured 512 B a draw more than a single frame here on the
         * base commit, and 0 B once they are plain floats.
         */
        const val BOUND = 256L
    }
}
