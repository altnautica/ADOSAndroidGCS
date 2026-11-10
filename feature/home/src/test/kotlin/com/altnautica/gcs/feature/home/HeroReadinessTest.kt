package com.altnautica.gcs.feature.home

import com.altnautica.gcs.core.link.node.NodeVitals
import com.altnautica.gcs.core.model.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hero reads "Ready to fly" only once battery, mode and GPS are known. */
class HeroReadinessTest {

    private val flyOpen = SurfaceCard(HomeSurface.Fly, enabled = true, reason = null)
    private val known = NodeVitals(batteryPct = 80, modeName = "LOITER", armed = false, gpsFix = GpsFix.Fix3D, satellites = 12)

    @Test
    fun knownVitalsOnAnOpenFlyCardReadAsReady() {
        assertTrue(vitalsKnown(known))
        assertEquals(HeroReadiness.Ready, heroReadiness(flyOpen, known))
    }

    @Test
    fun unknownVitalsReadAsConnecting() {
        assertEquals(HeroReadiness.Connecting, heroReadiness(flyOpen, NodeVitals.UNKNOWN))
    }

    @Test
    fun anySingleUnknownVitalReadsAsConnecting() {
        for (partial in listOf(known.copy(batteryPct = null), known.copy(modeName = null), known.copy(gpsFix = null))) {
            assertFalse(vitalsKnown(partial))
            assertEquals(HeroReadiness.Connecting, heroReadiness(flyOpen, partial))
        }
    }

    @Test
    fun armedStateAndSatellitesAreNotNeededForReady() {
        assertEquals(HeroReadiness.Ready, heroReadiness(flyOpen, known.copy(armed = null, satellites = null)))
    }

    @Test
    fun aDisabledFlyCardShowsItsReasonWhateverTheVitals() {
        val blocked = SurfaceCard(HomeSurface.Fly, enabled = false, reason = R.string.home_reason_offline)
        assertEquals(HeroReadiness.Blocked(R.string.home_reason_offline), heroReadiness(blocked, known))
        assertEquals(HeroReadiness.Blocked(R.string.home_reason_offline), heroReadiness(blocked, NodeVitals.UNKNOWN))
    }
}
