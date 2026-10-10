package com.altnautica.gcs.core.video.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaledBufferSizeTest {

    @Test
    fun `half scale halves each side and keeps the aspect`() {
        assertEquals(540 to 304, scaledBufferSize(1080, 608, 0.5f))
        assertEquals(1200 to 540, scaledBufferSize(2400, 1080, 0.5f))
    }

    @Test
    fun `odd results round down to even pixels`() {
        assertEquals(500 to 280, scaledBufferSize(1001, 561, 0.5f))
    }

    @Test
    fun `a view too small to scale yields an empty size the caller skips`() {
        assertEquals(0 to 0, scaledBufferSize(1, 1, 0.5f))
    }
}
