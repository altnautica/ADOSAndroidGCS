package com.altnautica.gcs.feature.cockpit.hud.layers

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The AR goal ring reads blocked when its camera's way does, as the path drawn to it does. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayerPainterGoalTest {
    private val density = Density(2f)

    /** The colour at the goal's centre with a way in [state] drawn to it (along the top edge, clear of the goal). */
    private fun goalColour(state: String?): Color {
        val measurer = TextMeasurer(createFontFamilyResolver(ApplicationProvider.getApplicationContext()), density, LayoutDirection.Ltr)
        val items = listOf(
            ArItem(t = "fpath", k = "fpath", state = state, pts = listOf(listOf(0.1f, 0.1f), listOf(0.4f, 0.1f))),
            ArItem(t = "fgoal", k = "fgoal", x = 0.5f, y = 0.5f),
        )
        val slot = LayerSlot<ArFrame>().apply { push(ArFrame(cameras = mapOf("main" to ArCamera(items = items))), stamp = 1, atMs = 0) }
        val bitmap = ImageBitmap(W, H)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(W.toFloat(), H.toFloat())) {
            with(LayerPainter(measurer)) {
                drawAr(slot, "main", Rect(0f, 0f, W.toFloat(), H.toFloat()), LayerFlags(LayerLevel.Full, setOf(LayerSpec.AR_MOTION)), null, 1_000L, 1f)
            }
        }
        return bitmap.toPixelMap()[W / 2, H / 2]
    }

    @Test
    fun `the goal ring is blocked red while the way to it is blocked`() {
        assertEquals(LayerColors.Blocked, goalColour("blocked"))
    }

    @Test
    fun `the goal ring keeps the follow colour on a clear or detouring way`() {
        assertEquals(LayerColors.Follow, goalColour("clear"))
        assertEquals(LayerColors.Follow, goalColour("detour"))
        assertEquals(LayerColors.Follow, goalColour(null))
    }

    private companion object {
        const val W = 320
        const val H = 180
    }
}
