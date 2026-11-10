package com.altnautica.gcs.feature.cockpit.autonomy

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** HUD v2 nav-source chip vectors (N1-N6), radar (R1-R2) and minimap tile anchor (M1). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NavSourceTest {
    private val text = TargetText(testSpec.setupStrings)

    private fun inputs(
        flags: Int?,
        age: Long? = 100,
        nav: NavState? = null,
        params: Map<String, Int> = emptyMap(),
        fix: Int? = null,
        sats: Int? = null,
        hdop: Float? = null,
    ) = NavInputs(flags, age, nav, navFresh = nav != null, commandedSet = null, params = params, gpsFixType = fix, sats = sats, hdop = hdop)

    private fun nav(set: Int? = null, health: ExtNavHealth? = null, mode: String? = null) = NavState(mode, set, health, null, null, null, null)

    @Test
    fun `N1 GPS set reads GPS 3D with satellites and GOOD`() {
        val chip = NavSource.resolve(inputs(2 + 16, params = mapOf("EK3_SRC1_POSXY" to 3), fix = 3, sats = 14, hdop = 0.9f))
        assertEquals("GPS 3D·14", text.navLabel(chip))
        assertEquals(NavQuality.Good, chip.quality)
        assertEquals(NavTone.Good, chip.tone)
    }

    @Test
    fun `N2 external nav degraded`() {
        val chip = NavSource.resolve(inputs(2 + 8, nav = nav(1, ExtNavHealth.Degraded), params = mapOf("EK3_SRC1_POSXY" to 6)))
        assertEquals("EXT NAV", text.navLabel(chip))
        assertEquals("DEGRADED", text.quality(chip.quality!!))
        assertEquals(NavTone.Warn, chip.tone)
    }

    @Test
    fun `N3 flow set from vision-nav active set 2`() {
        val chip = NavSource.resolve(inputs(2, nav = nav(2), params = mapOf("EK3_SRC2_POSXY" to 0, "EK3_SRC2_VELXY" to 5)))
        assertEquals("FLOW", text.navLabel(chip))
        assertEquals("HOLD OK", text.quality(chip.quality!!))
        assertEquals(NavTone.Good, chip.tone)
    }

    @Test
    fun `N4 constant position mode is dead reckoning`() {
        val chip = NavSource.resolve(inputs(128))
        assertEquals("DR", text.navLabel(chip))
        assertEquals("DRIFT", text.quality(chip.quality!!))
        assertEquals(NavTone.Crit, chip.tone)
    }

    @Test
    fun `N5 unknown params derive external nav from vision-nav`() {
        val chip = NavSource.resolve(inputs(2 + 8, nav = nav(health = ExtNavHealth.Good, mode = "external_nav")))
        assertEquals("EXT NAV", text.navLabel(chip))
        assertEquals("GOOD", text.quality(chip.quality!!))
    }

    @Test
    fun `N6 stale EKF report reads NAV dash`() {
        val chip = NavSource.resolve(inputs(2 + 16, age = 2_500))
        assertEquals("NAV —", text.navLabel(chip))
        assertEquals(NavTone.Muted, chip.tone)
    }

    @Test
    fun `R1 a 2 m return at 90 degrees sits 20 px right of centre`() {
        val ranges = FloatArray(360).also { it[90] = 2f }
        val scan = ScanFrame(0f, Math.toRadians(1.0).toFloat(), ranges, 0)
        val dots = LocalFrame.scanDots(scan, 6f, 100f, 100f, 60f, 360)
        assertEquals(2, dots.size)
        assertEquals(120f, dots[0], 1e-3f)
        assertEquals(100f, dots[1], 1e-3f)
    }

    @Test
    fun `R2 nearest sector 180 cm reads DANGER`() {
        assertEquals(ProximityWord.Danger, LocalFrame.proximityWord(1.8f))
        assertEquals("1.8", HudGeometry.oneDecimal(1.8f))
    }

    @Test
    fun `M1 tile anchor is its north-west corner`() {
        val (n, e) = LocalFrame.tileAnchor(0, -1, 0.05f)
        assertEquals(3.2f, n, 1e-5f)
        assertEquals(-3.2f, e, 1e-5f)
    }

    @Test
    fun `radar auto scale`() {
        assertEquals(3f, LocalFrame.displayRange(RadarScale.Auto, 1.2f))
        assertEquals(6f, LocalFrame.displayRange(RadarScale.Auto, 4f))
        assertEquals(12f, LocalFrame.displayRange(RadarScale.Auto, null))
        assertEquals(6f, LocalFrame.displayRange(RadarScale.Six, 1.2f))
    }
}
