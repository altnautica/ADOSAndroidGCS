package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.cockpit.autonomy.Detection
import com.altnautica.gcs.feature.cockpit.autonomy.FollowEvent
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.LockState
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.specText

private const val REACQUIRED_WORD_MS = 4_000L

/** The release button's touch target. */
internal val LOCK_RELEASE_TARGET = 48.dp

/** Test tag of the release button. */
internal const val LOCK_RELEASE_TAG = "lock-release"

/**
 * What's-locked chip: who is designated, the state word (follow phase first, then a
 * recent re-acquire, then the tracker's lock word) and the live confidence, with `×`
 * to release (a 48 dp target). A chrome panel; the icon is the track colour.
 */
@Composable
internal fun LockChip(
    trackId: Int?,
    detection: Detection?,
    feedFresh: Boolean,
    follow: FollowState?,
    followFresh: Boolean,
    lastReacquired: FollowEvent.Reacquired?,
    nowMs: Long,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AdosTheme.colors
    val who = specText("lock.who", "label" to TargetState.classWord(detection?.classLabel ?: "").ifBlank { "—" }, "id" to (trackId ?: 0).toString())
    val word = when {
        !feedFresh -> specText("target.waiting_for_feed")
        follow != null && followFresh && follow.phase != FollowPhase.Idle -> when (follow.phase) {
            FollowPhase.Following -> specText("follow.phase.following").lowercase().replaceFirstChar(Char::uppercase)
            FollowPhase.Acquiring -> specText("follow.phase.acquiring").lowercase().replaceFirstChar(Char::uppercase)
            FollowPhase.Coasting -> specText("lock.coasting", "s" to HudGeometry.oneDecimal(follow.coastRemainingS ?: 0f))
            FollowPhase.Searching -> specText("follow.phase.searching").lowercase().replaceFirstChar(Char::uppercase)
            FollowPhase.Holding -> specText("lock.holding", "label" to (TargetState.holdReason(follow.holdReason) ?: ""))
            else -> specText("follow.phase.lost").lowercase().replaceFirstChar(Char::uppercase)
        }
        lastReacquired != null && nowMs - lastReacquired.atMs <= REACQUIRED_WORD_MS ->
            specText("lock.reacquired", "sim" to String.format(java.util.Locale.ROOT, "%.2f", lastReacquired.similarity))
        detection == null -> specText("lock.not_in_view")
        detection.lockState == LockState.Uncertain -> specText("lock.uncertain")
        detection.lockState == LockState.Lost -> specText("follow.phase.lost").lowercase().replaceFirstChar(Char::uppercase)
        else -> specText("lock.locked")
    }
    val chrome = LocalChrome.current
    ChromePanel(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.padding(start = chrome.pad).heightIn(min = chrome.control), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, tint = c.hudTrack, modifier = Modifier.size(chrome.icon))
            Spacer(Modifier.width(chrome.gap))
            Column {
                Text(who, style = chrome.labelText, color = chrome.text)
                Text(word, style = chrome.labelText, color = chrome.textDim)
            }
            Spacer(Modifier.width(chrome.gap))
            Text(detection?.let { "${TargetState.pct(it.confidence)}%" } ?: "—", style = chrome.valueText, color = chrome.text)
            // A 48 dp release target, whatever the chip's own height.
            IconButton(onClick = onRelease, modifier = Modifier.size(LOCK_RELEASE_TARGET).testTag(LOCK_RELEASE_TAG)) {
                Icon(Icons.Outlined.Close, contentDescription = specText("target.release_target"), tint = chrome.text, modifier = Modifier.size(chrome.icon))
            }
        }
    }
}
