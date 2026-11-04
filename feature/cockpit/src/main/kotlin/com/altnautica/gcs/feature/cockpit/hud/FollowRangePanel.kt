package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPhase
import com.altnautica.gcs.feature.cockpit.autonomy.FollowState
import com.altnautica.gcs.feature.cockpit.autonomy.HudGeometry
import com.altnautica.gcs.feature.cockpit.autonomy.RangeCue
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.skillbar.isWide

private const val DASH = "—"
private const val LATENCY_WARN_MS = 250f

/**
 * Following-mode range panel (L2, read-only): range and phase, the ± 2σ interval bar,
 * AGL with its source, geometry, and the range cues with latency and re-acquire mode.
 * Compact shows one line, medium three rows, expanded all five. A [stale] state shows
 * dashes at half opacity with the phase word STALE.
 */
@Composable
internal fun FollowRangePanel(follow: FollowState, stale: Boolean, width: WidthClass, modifier: Modifier = Modifier) {
    val c = AdosTheme.colors
    val small = AdosTypography.numeric.small.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val range = follow.rangeM?.takeIf { !stale }
    val sigma = follow.rangeSigmaM?.takeIf { !stale }
    val (phaseWord, phaseColor) = phaseWord(follow.phase, stale)
    val aglSource = when (follow.aglSource) {
        "rng" -> stringResource(R.string.hud_range_rng) to c.hudInk
        "baro" -> stringResource(R.string.hud_range_baro) to c.hudWarn
        else -> DASH to c.hudMuted
    }
    val agl = follow.aglM?.takeIf { !stale }?.let(HudGeometry::oneDecimal) ?: DASH
    val description = stringResource(R.string.hud_range_description)
    Surface(
        modifier = modifier.alpha(if (stale) 0.5f else 1f).semantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp),
        color = c.hudGlassStrong,
        contentColor = c.hudInk,
        border = BorderStroke(1.dp, c.hudHair),
    ) {
        Column(Modifier.padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S2), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (width == WidthClass.Compact) {
                Text(
                    stringResource(
                        R.string.hud_range_compact,
                        range?.let(HudGeometry::formatNumber) ?: DASH,
                        sigma?.let { HudGeometry.oneDecimal(2 * it) } ?: DASH,
                        agl,
                        aglSource.first,
                    ),
                    style = small,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    range?.let(HudGeometry::formatRange) ?: DASH,
                    style = AdosTypography.numeric.display.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.width(AdosSpacing.S4))
                Text(phaseWord, style = small, color = phaseColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IntervalBar(range, sigma)
                Spacer(Modifier.width(AdosSpacing.S2))
                Text(if (range != null && sigma != null) HudGeometry.rangeInterval(range, sigma) else DASH, style = small)
            }
            Row {
                Text(stringResource(R.string.hud_range_agl, agl, ""), style = small)
                Text(aglSource.first, style = small, color = aglSource.second)
            }
            if (width.isWide()) {
                Text(
                    stringResource(
                        R.string.hud_range_geometry,
                        follow.depressionDeg?.takeIf { !stale }?.let { Math.round(it).toString() } ?: DASH,
                        follow.bearingDeg?.takeIf { !stale }?.let(HudGeometry::bearing3) ?: DASH,
                        follow.targetSpeedMps?.takeIf { !stale }?.let(HudGeometry::oneDecimal) ?: DASH,
                    ),
                    style = small,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
                    Cue(stringResource(R.string.hud_range_cue_ground), RangeCue.Ground in follow.rangeCues && !stale)
                    Cue(stringResource(R.string.hud_range_cue_box), RangeCue.BoxHeight in follow.rangeCues && !stale)
                    Cue(stringResource(R.string.hud_range_cue_lidar), RangeCue.Lidar in follow.rangeCues && !stale)
                    val lat = follow.pipelineLatencyMs?.takeIf { !stale }
                    Text(
                        stringResource(R.string.hud_range_latency, lat?.let { Math.round(it).toString() } ?: DASH),
                        style = small,
                        color = if (lat != null && lat > LATENCY_WARN_MS) c.hudWarn else c.hudInk,
                    )
                    val mode = when (follow.reacquireMode) {
                        "strict" -> stringResource(R.string.hud_rail_strict)
                        "smart" -> stringResource(R.string.hud_rail_smart)
                        else -> DASH
                    }
                    Text(mode, style = small)
                }
            }
        }
    }
}

@Composable
private fun phaseWord(phase: FollowPhase, stale: Boolean): Pair<String, Color> {
    val c = AdosTheme.colors
    if (stale) return stringResource(R.string.hud_range_stale) to c.hudWarn
    return when (phase) {
        FollowPhase.Following -> stringResource(R.string.hud_range_following) to c.hudTrack
        FollowPhase.Acquiring -> stringResource(R.string.hud_range_acquiring) to c.hudTrack
        FollowPhase.Coasting -> stringResource(R.string.hud_range_coasting) to c.hudCoast
        FollowPhase.Searching -> stringResource(R.string.hud_range_searching) to c.hudCoast
        FollowPhase.Holding -> stringResource(R.string.hud_range_hold) to c.hudWarn
        FollowPhase.Lost -> stringResource(R.string.hud_range_lost) to c.hudCrit
        FollowPhase.Idle -> DASH to c.hudMuted
    }
}

@Composable
private fun Cue(label: String, lit: Boolean) {
    val c = AdosTheme.colors
    Text(
        label,
        style = AdosTypography.numeric.small.copy(fontSize = 11.sp, textDecoration = if (lit) null else TextDecoration.LineThrough),
        color = if (lit) c.hudInk else c.hudMuted,
    )
}

/** 160 dp track on a 0..max(20, ceil5(r + 2σ)) m scale, the ± 2σ segment filled and a tick at the range. */
@Composable
private fun IntervalBar(range: Float?, sigma: Float?) {
    val c = AdosTheme.colors
    Canvas(Modifier.width(160.dp).height(10.dp)) {
        val y = size.height / 2
        drawLine(c.hudHair, Offset(0f, y), Offset(size.width, y), strokeWidth = 5.dp.toPx())
        if (range == null || sigma == null) return@Canvas
        val max = HudGeometry.rangeScaleMax(range, sigma)
        fun x(m: Float) = (m / max).coerceIn(0f, 1f) * size.width
        drawLine(c.hudInk.copy(alpha = 0.6f), Offset(x(range - 2 * sigma), y), Offset(x(range + 2 * sigma), y), strokeWidth = 5.dp.toPx())
        drawLine(c.hudInk, Offset(x(range), 0f), Offset(x(range), size.height), strokeWidth = 2.dp.toPx())
    }
}
