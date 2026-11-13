package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.feature.cockpit.CompositionCounter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A panel carries its blur layer only once a video frame was copied behind it: until then
 * (always, on an emulator) the window composites no blur pass for a panel that shows none.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChromePanelBlurTest {
    @get:Rule
    val compose = createComposeRule()

    private val counter = CompositionCounter()

    @After
    fun tearDown() = Composer.setTracer(CompositionCounter.Off)

    @Test
    fun `a panel adds its blur layer only after the first copied frame`() {
        val backdrop = ChromeBackdrop()
        Composer.setTracer(counter)
        compose.setContent {
            CompositionLocalProvider(LocalChromeBackdrop provides backdrop) {
                ChromePanel(Modifier.size(120.dp, 40.dp)) {}
            }
        }
        compose.waitForIdle()
        counter.endFrame()
        assertTrue("the panel composed", (counter.counts[PANEL] ?: 0) > 0)
        assertEquals("blur layers without a copied frame", 0, counter.counts[FILL] ?: 0)

        compose.runOnIdle { backdrop.frame = ImageBitmap(20, 10) }
        compose.waitForIdle()
        counter.endFrame()
        assertTrue("the blur layer follows the first copied frame", (counter.counts[FILL] ?: 0) > 0)
    }

    private companion object {
        const val PANEL = "com.altnautica.gcs.feature.cockpit.chrome.ChromePanel"
        const val FILL = "com.altnautica.gcs.feature.cockpit.chrome.BackdropFill"
    }
}
