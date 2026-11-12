package com.altnautica.gcs.feature.cockpit.video

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A failed video offers Retry under its words; other states do not. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VideoRetryTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(state: VideoState, onRetry: () -> Unit) = compose.setContent {
        AdosTheme {
            CompositionLocalProvider(LocalCockpitStrings provides testSpec.setupStrings) {
                VideoPlaceholder(state, "words", onRetry)
            }
        }
    }

    @Test
    fun `a failed video shows Retry, which dials again`() {
        var retries = 0
        show(VideoState.Failed("ice")) { retries++ }
        compose.onNodeWithTag(TAG_VIDEO_RETRY).performClick()
        compose.waitForIdle()
        assertEquals(1, retries)
    }

    @Test
    fun `a connecting video has no Retry`() {
        show(VideoState.Connecting) {}
        compose.onNodeWithTag(TAG_VIDEO_RETRY).assertDoesNotExist()
    }
}
