package com.altnautica.gcs.feature.cockpit.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropSamplingTest {

    @Test
    fun `the panel backdrop is copied once a second`() {
        assertEquals(1_000L, BACKDROP_SAMPLE_MS)
    }

    @Test
    fun `an emulator never reads the video SurfaceView back, a phone does`() {
        assertFalse(copiesBackdrop(surfaceView = true, emulator = true))
        assertTrue(copiesBackdrop(surfaceView = true, emulator = false))
        assertTrue("a TextureView copy does not touch the video swap", copiesBackdrop(surfaceView = false, emulator = true))
    }
}
