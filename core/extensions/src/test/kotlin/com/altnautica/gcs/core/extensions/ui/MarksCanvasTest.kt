package com.altnautica.gcs.core.extensions.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.extensions.marks.CockpitMark
import com.altnautica.gcs.core.extensions.marks.MarkSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory

/**
 * Extension marks are read by the layer's draw pass only, and each posted set is laid out
 * once: new marks never recompose the layer, and a redraw of the same set allocates nothing
 * per mark.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarksCanvasTest {
    @get:Rule
    val compose = createComposeRule()

    private fun marks(i: Int): List<CockpitMark> = listOf(
        CockpitMark.Box("box", MarkSpace.Normalized, "#22C55E", 0.1 + i * 0.001, 0.1, 0.2, 0.2, dashed = true),
        CockpitMark.Reticle("ret", MarkSpace.Normalized, null, 0.5, 0.5, 0.1, 0.1),
        CockpitMark.Point("pt", MarkSpace.Normalized, "#F00", 0.3, 0.7, 5.0),
        CockpitMark.Polyline("line", MarkSpace.Normalized, null, List(20) { k -> k * 0.05 to 0.5 + (k % 3) * 0.02 }, 2.0),
        CockpitMark.Label("label", MarkSpace.Normalized, "#FFFFFF", 0.6, 0.2, "TARGET $i"),
    )

    @Test
    fun `new marks redraw the layer without recomposing it`() {
        val state = mutableStateOf(marks(0))
        var recompositions = 0
        compose.setContent {
            MarksCanvas({ state.value }, Modifier.size(320.dp, 180.dp), recompositionProbe = { recompositions++ })
        }
        compose.waitForIdle()
        val composed = recompositions
        repeat(30) { i ->
            state.value = marks(i + 1)
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        assertEquals("posted marks never recompose the layer", composed, recompositions)
    }

    @Test
    fun `a redraw of the same marks allocates nothing per mark`() {
        val density = Density(2f)
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)
        val painter = MarksPainter(measurer)
        val set = marks(1)
        val canvas = Canvas(ImageBitmap(640, 360))
        val scope = CanvasDrawScope()
        fun drawOnce() = scope.draw(density, LayoutDirection.Ltr, canvas, Size(640f, 360f)) {
            with(painter) { draw(set, null, Color.Green) }
        }
        repeat(20) { drawOnce() }
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = bean.currentThreadAllocatedBytes
        repeat(DRAWS) { drawOnce() }
        val perDraw = (bean.currentThreadAllocatedBytes - before) / DRAWS
        // Re-mapping the polyline's twenty points (a pair and two boxed floats each), a new path,
        // three strokes, a dash effect and a label re-measure cost several KiB a draw; the
        // shadow canvas's own share for these few calls stays well under the bound.
        assertTrue("steady marks draw allocated $perDraw bytes per draw", perDraw < 3_072)
    }

    private companion object {
        const val DRAWS = 50
    }
}
