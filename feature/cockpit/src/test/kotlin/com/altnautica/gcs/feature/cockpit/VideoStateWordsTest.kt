package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.VideoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The video placeholder speaks the shared `video.state.*` words, never a transport error. */
class VideoStateWordsTest {
    private fun word(s: VideoState) = testSpec.setupStrings.format(videoStateKey(s))

    @Test
    fun `each video state has its spec word`() {
        assertEquals("Connecting to video…", word(VideoState.Connecting))
        assertEquals("Picture frozen", word(VideoState.Frozen(0)))
        assertEquals("No video source", word(VideoState.NoSource))
        assertEquals("No video source", word(VideoState.Idle))
    }

    @Test
    fun `a failed video never shows the transport error`() {
        val w = word(VideoState.Failed("connect to video-host:8889 failed"))
        assertEquals("Video failed", w)
        assertFalse(w.contains("8889"))
    }
}
