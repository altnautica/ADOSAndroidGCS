package com.altnautica.gcs.feature.home

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hero still is current or gone: no frame clears it; an undecodable frame keeps the last. */
class ThumbnailTest {
    @Test
    fun noFrameClearsTheStill() = runBlocking {
        assertNull(nextThumbnail("old", null) { "new" })
    }

    @Test
    fun aNewFrameReplacesTheStill() = runBlocking {
        assertEquals("new", nextThumbnail("old", byteArrayOf(1)) { "new" })
    }

    @Test
    fun anUndecodableFrameKeepsTheLastStill() = runBlocking {
        assertEquals("old", nextThumbnail<String>("old", byteArrayOf(1)) { null })
    }
}
