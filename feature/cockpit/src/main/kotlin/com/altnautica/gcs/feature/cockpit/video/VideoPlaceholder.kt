package com.altnautica.gcs.feature.cockpit.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.feature.cockpit.flow.specText

/**
 * The words in place of a video that is not live ([text], the shared `video.state.*` word,
 * never the raw transport error), with Retry under a failed video.
 */
@Composable
internal fun VideoPlaceholder(state: VideoState, text: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = AdosTheme.colors.mediaOn, modifier = Modifier.testTag("video-placeholder"))
        if (state is VideoState.Failed) {
            AdosButton(specText("video.state.retry"), onClick = onRetry, variant = AdosButtonVariant.Secondary, modifier = Modifier.testTag(TAG_VIDEO_RETRY))
        }
    }
}

const val TAG_VIDEO_RETRY = "video-retry"
