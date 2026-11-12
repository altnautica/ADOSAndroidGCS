package com.altnautica.gcs.feature.cockpit

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.feature.cockpit.chrome.flightSizes
import com.altnautica.gcs.feature.cockpit.chrome.flightZones
import com.altnautica.gcs.feature.cockpit.hud.HEADING_READOUT_HEIGHT
import com.altnautica.gcs.feature.cockpit.hud.HEADING_READOUT_LIFT
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The centre's banners never draw across the heading readout above the reticle. */
class FlowBannerZoneTest {
    /** The centre zone's height for a landscape window. */
    private fun centreHeight(w: Dp, h: Dp, width: WidthClass): Dp = flightZones(w, h, flightSizes(width), gimbal = true).centre.height

    private fun assertClear(centre: Dp) {
        val readoutBottom = centre / 2 - HEADING_READOUT_LIFT
        val readoutTop = readoutBottom - HEADING_READOUT_HEIGHT
        val top = centreBannerTop(centre)
        val bottom = top + BANNER_HEIGHT
        assertTrue("centre $centre: banner $top..$bottom vs readout $readoutTop..$readoutBottom", bottom <= readoutTop || top >= readoutBottom)
    }

    @Test
    fun `on a phone the banners sit below the heading readout`() {
        val centre = centreHeight(780.dp, 360.dp, WidthClass.Compact)
        assertTrue(centreBannerTop(centre) > BANNER_TOP)
        assertClear(centre)
    }

    @Test
    fun `on a tablet the banners stay under the top bar, above the readout`() {
        val centre = centreHeight(1280.dp, 800.dp, WidthClass.Expanded)
        assertEquals(BANNER_TOP, centreBannerTop(centre))
        assertClear(centre)
    }

    @Test
    fun `no centre height puts a banner across the readout`() {
        var h = 120.dp
        while (h <= 900.dp) {
            assertClear(h)
            h += 10.dp
        }
    }
}
