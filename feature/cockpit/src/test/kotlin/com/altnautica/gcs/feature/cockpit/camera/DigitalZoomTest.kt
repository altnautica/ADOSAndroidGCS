package com.altnautica.gcs.feature.cockpit.camera

import com.altnautica.gcs.feature.cockpit.digitalZoomStep
import org.junit.Assert.assertEquals
import org.junit.Test

class DigitalZoomTest {
    @Test
    fun `digital zoom steps 1x 2x 4x and stops at the ends`() {
        assertEquals(2, digitalZoomStep(1, 1))
        assertEquals(4, digitalZoomStep(2, 1))
        assertEquals(4, digitalZoomStep(4, 1))
        assertEquals(2, digitalZoomStep(4, -1))
        assertEquals(1, digitalZoomStep(1, -1))
    }
}
