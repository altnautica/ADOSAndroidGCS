package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.Sensing
import com.altnautica.gcs.core.model.SensingTier
import com.altnautica.gcs.core.skills.adosPilot
import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sensing chip: the tier in the spec's words and its speed limit, only while the pilot reports them. */
class SensingTextTest {
    private val text = SensingText(SpecText(testSpec.setupStrings))

    @Test
    fun `the chip names the tier and the speed limit at most one decimal`() {
        val lidar = adosPilot().copy(sensing = Sensing(SensingTier.Lidar3d, 11.6f, 4.24f))
        assertEquals("Sensing: 3D lidar · ≤ 4.2 m/s", text.chip(lidar))
        val cases = mapOf(
            SensingTier.Depth to "Sensing: Depth camera · ≤ 3 m/s",
            SensingTier.Camera to "Sensing: Camera · ≤ 3 m/s",
            SensingTier.Planar to "Sensing: 2D lidar · ≤ 3 m/s",
        )
        for ((tier, want) in cases) assertEquals(want, text.chip(adosPilot().copy(sensing = Sensing(tier, 6f, 3f))))
    }

    @Test
    fun `nothing shows while the pilot reports no sensing`() {
        assertNull(text.chip(adosPilot()))
        assertNull(text.chip(null))
    }
}
