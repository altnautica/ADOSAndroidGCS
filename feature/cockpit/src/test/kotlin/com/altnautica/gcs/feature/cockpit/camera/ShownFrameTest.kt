package com.altnautica.gcs.feature.cockpit.camera

import android.view.Window
import androidx.compose.ui.geometry.Rect
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShownFrameTest {
    @Test
    fun `at 4x the copy takes the centred quarter of the buffer into a slot-sized bitmap`() {
        // A 2400x1080 slot at 4x: the view (and its buffer) is 9600x4320.
        val copy = shownCopy(9_600, 4_320, 2_400, 1_080, 4f)!!
        assertEquals(PixelBox(3_600, 1_620, 6_000, 2_700), copy.source)
        assertEquals(2_400 to 1_080, copy.outWidth to copy.outHeight)
    }

    @Test
    fun `a scaled-down buffer is cropped in its own pixels, and 1x copies it whole`() {
        val half = shownCopy(1_200, 540, 1_200, 540, 2f)!!
        assertEquals(PixelBox(300, 135, 900, 405), half.source)
        assertEquals(1_200 to 540, half.outWidth to half.outHeight)
        assertEquals(PixelBox(0, 0, 1_200, 540), shownCopy(1_200, 540, 1_200, 540, 1f)!!.source)
    }

    @Test
    fun `an empty buffer or slot copies nothing`() {
        assertNull(shownCopy(0, 1_080, 2_400, 1_080, 1f))
        assertNull(shownCopy(2_400, 1_080, 0, 0, 2f))
    }

    @Test
    fun `a bitmap that cannot be allocated leaves no frame and no crash`() = runTest {
        val video = ShownVideo(zoom = 2f).apply { bounds = Rect(0f, 0f, 2_400f, 1_080f) }
        val asked = mutableListOf<Pair<Int, Int>>()
        val frame = copyShownFrame(video, mockk<Window>(relaxed = true)) { w, h ->
            asked += w to h
            throw OutOfMemoryError("no room for the frame")
        }
        assertNull(frame)
        assertEquals("the copy is sized to the slot", listOf(2_400 to 1_080), asked)
    }

    @Test
    fun `a press saves its copy only when the camera did not take the photo`() = runTest {
        val saved = mutableListOf<String>()
        suspend fun outcome(live: Boolean, keep: Boolean?, copied: String?, saves: Boolean = true): String? {
            val answer = CompletableDeferred<Boolean>()
            if (keep == null) answer.completeExceptionally(IllegalStateException("link lost")) else answer.complete(keep)
            return frameGrabOutcome(live, answer, copy = { copied }, save = { f -> saved += f; saves })
        }
        assertNull("the camera took it", outcome(live = true, keep = false, copied = "a"))
        assertEquals(KEY_SAVED, outcome(live = true, keep = true, copied = "b"))
        assertEquals("a failed command counts as not taken", KEY_SAVED, outcome(live = true, keep = null, copied = "c"))
        assertEquals(KEY_NOT_SAVED, outcome(live = true, keep = true, copied = null))
        assertEquals(KEY_NOT_SAVED, outcome(live = true, keep = true, copied = "d", saves = false))
        assertEquals(KEY_NO_VIDEO, outcome(live = false, keep = true, copied = "e"))
        assertEquals(listOf("b", "c", "d"), saved)
    }

    @Test
    fun `the not-saved toast is the spec's text`() {
        assertEquals("Photo not saved", com.altnautica.gcs.feature.cockpit.testSpec.setupStrings.format(KEY_NOT_SAVED))
    }
}
