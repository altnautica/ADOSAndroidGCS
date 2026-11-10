package com.altnautica.gcs.feature.cockpit.camera

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.component.overlay.ProvideOverlayHost
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.core.model.StreamSwitchKind
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The camera-settings sheet over the flight screen: camera and stream, recording, digital zoom, and "No video". */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w914dp-h411dp-land")
class CameraSettingsSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val streams = listOf(
        StreamDescriptor("main", "front", "h264", live = true, switchKind = StreamSwitchKind.Switchable),
        StreamDescriptor("sub", "front low", "h265", live = true, switchKind = StreamSwitchKind.Switchable),
    )

    private fun ui(hasVideo: Boolean = true, zoom: Int = 1, recording: Boolean = false) = CameraSheetUi(
        streams = streams, selectedStreamId = "main", hasVideo = hasVideo, digitalZoom = zoom,
        canRecord = true, recording = recording, pipAvailable = false, pipOn = false,
    )

    private var dismissed = 0
    private var recordToggles = 0

    private fun show(ui: CameraSheetUi) {
        compose.setContent {
            AdosTheme {
                ProvideOverlayHost {
                    CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                        CameraSettingsSheet(ui, onDismiss = { dismissed++ }, onSelectStream = {}, onTogglePip = {}, onToggleRecording = { recordToggles++ })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun withVideoTheSheetOffersTheStreamAndRecording() {
        show(ui())
        compose.onNodeWithText("Camera settings").assertIsDisplayed()
        compose.onNodeWithText("Cameras").assertIsDisplayed()
        compose.onNodeWithTag("camera-sheet-no-video", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Start recording").performClick()
        compose.waitForIdle()
        assertEquals(1, recordToggles)
    }

    @Test
    fun recordingShowsStop() {
        show(ui(recording = true))
        compose.onNodeWithText("Stop recording").assertIsDisplayed()
    }

    @Test
    fun withoutVideoTheSheetSaysNoVideoAndOffersNoStreamOrRecording() {
        show(ui(hasVideo = false))
        compose.onNodeWithText("No video").assertIsDisplayed()
        compose.onNodeWithTag("camera-sheet-stream", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("camera-sheet-record", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun digitalZoomInUseIsNamed() {
        show(ui(zoom = 2))
        compose.onNodeWithText("Digital ×2").assertIsDisplayed()
    }

    @Test
    fun backClosesTheSheet() {
        show(ui())
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, dismissed)
    }

    @Test
    fun streamLabelNamesTheCameraAndItsCodec() {
        assertEquals("front · H264", streamLabel(streams[0]))
        assertEquals("cam2", streamLabel(StreamDescriptor("cam2", "", "", live = true, switchKind = StreamSwitchKind.Concurrent)))
    }

    @Test
    fun savedFramesGetDistinctTimestampedPngNames() {
        val tz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("ADOS_20261110_093000_000.png", frameFileName(1_794_303_000_000L, Locale.ROOT))
            assertFalse(frameFileName(1_794_303_000_000L) == frameFileName(1_794_303_000_001L))
        } finally {
            TimeZone.setDefault(tz)
        }
    }
}
