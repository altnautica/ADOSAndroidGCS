package com.altnautica.gcs.feature.cockpit

import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.feature.cockpit.chrome.ZoneRect
import com.altnautica.gcs.feature.cockpit.chrome.flightSizes
import com.altnautica.gcs.feature.cockpit.chrome.flightZones
import com.altnautica.gcs.feature.cockpit.pilot.ON_SCREEN_PAD
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vision panel and the instrument tapes keep to their zones on a phone and a tablet. */
class ZonePlacementTest {
    private fun overlaps(a: ZoneRect, b: ZoneRect) = a.x < b.right && b.x < a.right && a.y < b.bottom && b.y < a.bottom

    private val windows = listOf(
        Triple(914.dp, 411.dp, WidthClass.Expanded),
        Triple(1280.dp, 800.dp, WidthClass.Expanded),
        Triple(760.dp, 480.dp, WidthClass.Medium),
    )

    @Test
    fun `the vision panel sits clear of every chrome zone and the sticks`() {
        for ((w, h, cls) in windows) {
            val z = flightZones(w, h, flightSizes(cls), gimbal = true)
            val panel = visionPanelRect(z, ON_SCREEN_PAD)
            for ((name, rect) in z.named()) {
                if (name == "centre") continue
                assertFalse("vision panel over $name at $w x $h", overlaps(panel, rect))
            }
            val rightStick = ZoneRect(z.centre.right - ON_SCREEN_PAD, z.centre.bottom - ON_SCREEN_PAD, ON_SCREEN_PAD, ON_SCREEN_PAD)
            assertFalse("vision panel over the right stick at $w x $h", overlaps(panel, rightStick))
        }
    }

    @Test
    fun `with sticks on the tapes start inward of both pads`() {
        for ((w, h, cls) in windows) {
            val z = flightZones(w, h, flightSizes(cls), gimbal = true)
            val inset = centreInset(z, ON_SCREEN_PAD)
            val start: Dp = inset.calculateStartPadding(LayoutDirection.Ltr)
            val end: Dp = inset.calculateEndPadding(LayoutDirection.Ltr)
            val windowW = z.topBar.right + z.topBar.x
            assertTrue(start >= z.centre.x + ON_SCREEN_PAD)
            assertTrue(windowW - end <= z.centre.right - ON_SCREEN_PAD)
        }
    }

    @Test
    fun `sticks show only at the spec's classes`() {
        val classes = testSpec.layout.onScreenSticks.classes
        for (cls in WidthClass.entries) {
            assertTrue(sticksShown(true, cls, classes) == (layoutClassId(cls, landscape = true) in classes))
            assertFalse(sticksShown(false, cls, classes))
        }
    }
}
