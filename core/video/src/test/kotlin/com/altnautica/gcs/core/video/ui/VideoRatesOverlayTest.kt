package com.altnautica.gcs.core.video.ui

import android.view.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.video.VideoOutput
import com.altnautica.gcs.core.video.VideoRates
import com.altnautica.gcs.core.video.VideoRecording
import com.altnautica.gcs.core.video.VideoSessionHandle
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VideoRatesOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private class RatesHandle : VideoSessionHandle {
        override val source = VideoSource.AgentWhep(DeviceId("d1"), "http://192.168.1.50:8080/", "main")
        override val state = MutableStateFlow<VideoState>(VideoState.Live(null))
        override val output: VideoOutput = object : VideoOutput.SurfaceTarget {
            override fun setSurface(surface: Surface?) = Unit
        }
        override val recording: VideoRecording? = null
        override val rates = MutableStateFlow<VideoRates?>(null)
        override fun start() = Unit
        override suspend fun stop() = Unit
    }

    @Test
    fun `the overlay shows decoded against shown frames per second once measured`() {
        val handle = RatesHandle()
        compose.setContent { VideoRatesOverlay(handle) }
        compose.onNodeWithText("fps", substring = true).assertDoesNotExist()

        handle.rates.value = VideoRates(
            receivedFps = 27.0, decodedFps = 26.7, droppedFps = 0.0, deliveredFps = 14.0, shownFps = 12.0,
            interFrameMs = 37.0, decoder = "c2.goldfish.h264.decoder", latencyMs = 40,
        )
        compose.onNodeWithText(
            "c2.goldfish.h264.decoder · received 27.0 · decoded 26.7 · delivered 14.0 · shown 12.0 fps",
        ).assertIsDisplayed()
    }

    @Test
    fun `rates the stack does not report show as a dash`() {
        val rates = VideoRates(null, null, null, 25.0, 24.0, null, null, null)
        assertEquals(listOf("-", "-", "-", "25.0", "24.0"), rates.overlayArgs())
    }
}
