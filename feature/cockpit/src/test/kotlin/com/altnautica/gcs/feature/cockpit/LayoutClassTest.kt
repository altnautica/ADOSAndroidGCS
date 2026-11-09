package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.feature.cockpit.hud.layers.LayerLevel
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutClassTest {
    @get:Rule
    val compose = createComposeRule()

    private val classes = testSpec.layout.classes

    @Test
    fun `the layout class follows the width class in landscape and is portrait otherwise`() {
        assertEquals("tv", layoutClassId(WidthClass.Tv, landscape = true))
        assertEquals("compact", layoutClassId(WidthClass.Compact, landscape = true))
        assertEquals("portrait", layoutClassId(WidthClass.Expanded, landscape = false))
    }

    @Test
    fun `the spec's class values reach the layers and the level`() {
        val tv = classes["tv"]
        assertEquals(30, tv.layerPresentation().fpsCap)
        assertTrue(tv.focusRingsAlways)
        assertEquals(LayerLevel.Full, tv.defaultLayerLevel())
        for (id in listOf("compact", "portrait")) {
            val c = classes[id]
            assertEquals(id, LayerLevel.Route, c.defaultLayerLevel())
            assertTrue(id, c.layerPresentation().distanceOnly)
            assertTrue(id, c.distanceOnly)
            assertFalse(id, c.focusRingsAlways)
        }
    }

    private fun ringPixel(on: Boolean): Color {
        compose.setContent {
            val focus = remember { FocusRequester() }
            Box(Modifier.size(100.dp).background(Color.Black).focusRings(on, Color.Red).testTag("root")) {
                Box(Modifier.offset(30.dp, 30.dp).size(40.dp).focusRequester(focus).focusable())
            }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        compose.waitForIdle()
        val img = compose.onNodeWithTag("root").captureToImage().toPixelMap()
        val px = img.width / 100f
        // Just outside the focused element's left edge, half way down.
        return img[(29 * px).toInt(), (50 * px).toInt()]
    }

    @Test
    fun `the tv class rings the focused control`() {
        assertEquals(Color.Red, ringPixel(on = true))
    }

    @Test
    fun `other classes draw no ring`() {
        assertNotEquals(Color.Red, ringPixel(on = false))
    }
}
