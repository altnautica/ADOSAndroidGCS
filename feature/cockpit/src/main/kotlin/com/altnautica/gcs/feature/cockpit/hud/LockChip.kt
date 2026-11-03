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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.autonomy.Detection
import com.altnautica.gcs.feature.cockpit.autonomy.FollowEvent
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.LockState
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState

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
    val who = stringResource(R.string.hud_lock_who, TargetState.classWord(detection?.classLabel ?: "").ifBlank { "—" }, trackId ?: 0)
    val word = when {
        !feedFresh -> stringResource(R.string.hud_lock_waiting)
        follow != null && followFresh && follow.phase != FollowPhase.Idle -> when (follow.phase) {
            FollowPhase.Following -> stringResource(R.string.hud_lock_following)
            FollowPhase.Acquiring -> stringResource(R.string.hud_lock_acquiring)
            FollowPhase.Coasting -> stringResource(R.string.hud_lock_coasting, HudGeometry.oneDecimal(follow.coastRemainingS ?: 0f))
            FollowPhase.Searching -> stringResource(R.string.hud_lock_searching)
            FollowPhase.Holding -> stringResource(R.string.hud_lock_holding, TargetState.holdReason(follow.holdReason) ?: "")
            else -> stringResource(R.string.hud_lock_lost)
        }
        lastReacquired != null && nowMs - lastReacquired.atMs <= REACQUIRED_WORD_MS ->
            stringResource(R.string.hud_lock_reacquired, String.format(java.util.Locale.ROOT, "%.2f", lastReacquired.similarity))
        detection == null -> stringResource(R.string.hud_lock_not_in_view)
        detection.lockState == LockState.Uncertain -> stringResource(R.string.hud_lock_uncertain)
        detection.lockState == LockState.Lost -> stringResource(R.string.hud_lock_lost)
        else -> stringResource(R.string.hud_lock_locked)
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
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.hud_lock_release))
            }
        }
    }
}
