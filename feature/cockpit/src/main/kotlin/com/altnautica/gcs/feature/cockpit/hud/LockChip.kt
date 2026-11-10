package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.autonomy.Detection
import com.altnautica.gcs.feature.cockpit.autonomy.FollowEvent
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.LockState
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import com.altnautica.gcs.feature.cockpit.flow.specText

private const val REACQUIRED_WORD_MS = 4_000L

/**
 * What's-locked chip: who is designated, the state word (follow phase first, then a
 * recent re-acquire, then the tracker's lock word) and the live confidence, with `×`
 * to release. Border and icon are the track colour.
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
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(8.dp),
        color = c.hudGlassStrong,
        contentColor = c.hudInk,
        border = BorderStroke(1.2.dp, c.hudTrack),
    ) {
        Row(Modifier.padding(start = AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CenterFocusStrong, contentDescription = null, tint = c.hudTrack, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(AdosSpacing.S2))
            Column {
                Text(who, style = AdosTypography.numeric.small)
                Text(word, style = AdosTypography.numeric.small, color = c.hudInk2)
            }
            Spacer(Modifier.width(AdosSpacing.S3))
            Text(detection?.let { "${TargetState.pct(it.confidence)}%" } ?: "—", style = AdosTypography.numeric.small)
            IconButton(onClick = onRelease, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = specText("target.release_target"))
            }
        }
    }
}
