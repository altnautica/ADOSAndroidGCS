package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.LinkCellTarget
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.hud.LOCK_RELEASE_TAG
import com.altnautica.gcs.feature.cockpit.hud.LockChip
import com.altnautica.gcs.feature.cockpit.hud.ReacquireToggle
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Every flight-screen target is at least the spec's `touch_target.min` (48 dp) in both
 * directions, measured on the node's touch bounds: the top bar's Menu, STOP and link cell,
 * the card dots, the photo/video switch, each zoom half and the lock chip's release.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TouchTargetTest {
    @get:Rule
    val compose = createComposeRule()

    private val min = 48f

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        AdosTheme {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) { content() }
        }
    }

    /** The node's touch width and height in dp. */
    private fun touchDp(tag: String): Pair<Float, Float> {
        val r = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().touchBoundsInRoot
        val d = compose.density.density
        return r.width / d to r.height / d
    }

    private fun assertTarget(tag: String) {
        val (w, h) = touchDp(tag)
        assertTrue("$tag is $w × $h dp", w >= min - 0.5f && h >= min - 0.5f)
    }

    @Test
    fun `the spec's touch minimum is 48 dp`() {
        org.junit.Assert.assertEquals(48, testSpec.layout.touchTarget.min)
    }

    @Test
    fun `the top bar's Menu, STOP and link cell are 48 dp targets at the 36 dp control height`() {
        show {
            Column {
                MenuButton(onClick = {})
                StopButton(onClick = {})
                LinkCellTarget("Link: 0.4 s", onOpen = {}, modifier = Modifier.testTag("link-target")) { Text("0.4 s") }
            }
        }
        assertTarget("menu")
        assertTarget("stop")
        assertTarget("link-target")
    }

    @Test
    fun `each card dot is a 48 dp square`() {
        show {
            Box(Modifier.size(144.dp, 88.dp)) {
                SwitchCard(CardPage.Map, "Map", onStep = {}) {}
            }
        }
        for (p in CardPage.entries) assertTarget("card-dot-${p.name}")
    }

    @Test
    fun `the photo-video switch and each zoom half are 48 dp targets at phone size`() {
        val sizes = flightSizes(WidthClass.Compact)
        show {
            Column {
                PhotoVideoSwitch(false, true, "Photo mode", "Video mode", onVideo = {}, width = sizes.shutter, height = sizes.modeSwitch)
                ZoomPill("Zoom in", "Zoom out", onIn = {}, onOut = {}, onLongPress = null, width = sizes.rightButton, height = sizes.zoom)
            }
        }
        assertTarget("grid-mode-switch")
        assertTarget("grid-zoom-in")
        assertTarget("grid-zoom-out")
    }

    @Test
    fun `the lock chip's release is a 48 dp target`() {
        show {
            LockChip(
                trackId = 3,
                detection = null,
                feedFresh = true,
                follow = null,
                followFresh = false,
                lastReacquired = null,
                nowMs = 0L,
                onRelease = {},
            )
        }
        assertTarget(LOCK_RELEASE_TAG)
    }

    @Test
    fun `each half of the follow rail's Strict-Smart toggle is a 48 dp target`() {
        show { ReacquireToggle(mode = "strict", pending = null, size = 48.dp, onSelect = {}) }
        assertTarget("reacquire-strict")
        assertTarget("reacquire-smart")
    }
}
