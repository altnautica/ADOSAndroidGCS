package com.altnautica.gcs.feature.cockpit.band

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Battery5Bar
import androidx.compose.material.icons.outlined.BatteryUnknown
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Dangerous
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.GpsOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.feature.cockpit.BandState
import com.altnautica.gcs.feature.cockpit.BatteryBand
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.ReachBadge
import com.altnautica.gcs.feature.cockpit.StickChip
import com.altnautica.gcs.feature.cockpit.alerts.LinkHealth
import kotlinx.coroutines.delay

/** Height of the safety band at every density (CS-2). */
val SafetyBandHeight = 40.dp

private const val DASH = "—"

enum class ChipTone { Neutral, Good, Warn, Crit, Accent }

@Composable
private fun ChipTone.color(): Color {
    val c = AdosTheme.colors
    return when (this) {
        ChipTone.Neutral -> c.hudInk2
        ChipTone.Good -> c.hudGood
        ChipTone.Warn -> c.hudWarn
        ChipTone.Crit -> c.hudCrit
        ChipTone.Accent -> c.hudPrimary
    }
}

/** Icon plus label, so state is never carried by colour alone. */
@Composable
fun BandChip(
    icon: ImageVector,
    text: String,
    tone: ChipTone,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val tint = tone.color()
    val base = modifier
        .height(32.dp)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
    Surface(
        modifier = base,
        shape = RoundedCornerShape(AdosRadius.Md),
        color = AdosTheme.colors.hudGlassStrong,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.55f)),
        contentColor = AdosTheme.colors.hudInk,
    ) {
        Row(Modifier.padding(horizontal = AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AdosSize.IconSm))
            Spacer(Modifier.width(AdosSpacing.S1))
            Text(text, style = AdosTypography.numeric.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun reachLabel(badge: ReachBadge): String = stringResource(
    when (badge) {
        ReachBadge.Direct -> R.string.cockpit_reach_direct
        ReachBadge.Lan -> R.string.cockpit_reach_lan
        ReachBadge.ViaGround -> R.string.cockpit_reach_via_ground
        ReachBadge.Cloud -> R.string.cockpit_reach_cloud
        ReachBadge.PhoneRadio -> R.string.cockpit_reach_phone_radio
        ReachBadge.Blocked -> R.string.cockpit_reach_blocked
    },
)

@Composable
fun gpsLabel(fix: GpsFix?): String = stringResource(
    when (fix) {
        null -> R.string.cockpit_gps_unknown
        GpsFix.NoGps -> R.string.cockpit_gps_none
        GpsFix.NoFix -> R.string.cockpit_gps_no_fix
        GpsFix.Fix2D -> R.string.cockpit_gps_2d
        GpsFix.Fix3D -> R.string.cockpit_gps_3d
        GpsFix.DGps -> R.string.cockpit_gps_dgps
        GpsFix.RtkFloat -> R.string.cockpit_gps_rtk_float
        GpsFix.RtkFixed -> R.string.cockpit_gps_rtk_fixed
    },
)

/** m:ss for a duration in seconds. */
fun formatDuration(totalS: Long): String {
    val s = totalS.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Ticks once a second and shows the time since [startMs] on [clock]. Only this text recomposes. */
@Composable
private fun ElapsedChip(icon: ImageVector, startMs: Long, clock: () -> Long, tone: ChipTone, prefix: String) {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(startMs) {
        while (true) {
            now = clock()
            delay(1_000)
        }
    }
    BandChip(icon, "$prefix ${formatDuration((now - startMs) / 1000)}", tone)
}

/**
 * The always-visible safety band (CS-2): exit, node and reach, arm, mode, battery,
 * GPS, link, video, stick control, preflight, recording, flight time, Kill.
 */
@Composable
fun SafetyBand(
    state: BandState,
    onExit: () -> Unit,
    onToggleStick: () -> Unit,
    onOpenPreflight: () -> Unit,
    onOpenQuickSettings: () -> Unit,
    onOpenModes: () -> Unit,
    onKill: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    Surface(
        modifier = modifier.fillMaxWidth().height(SafetyBandHeight),
        color = colors.hudGlass,
        contentColor = colors.hudInk,
    ) {
        // Kill stays pinned at the trailing edge; the readouts scroll when the screen is too narrow for all of them.
        Row(Modifier.padding(horizontal = AdosSpacing.S1), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
            ) {
                IconButton(onClick = onExit, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.cockpit_exit))
                }
                Text(
                    state.nodeName ?: DASH,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = AdosSpacing.S1),
                )
                state.reach?.let { BandChip(Icons.Outlined.SettingsInputAntenna, reachLabel(it), ChipTone.Accent) }

                // Arm state, announced politely when it changes.
                val armText = when (state.armed) {
                    true -> stringResource(R.string.cockpit_armed)
                    false -> stringResource(R.string.cockpit_disarmed)
                    null -> DASH
                }
                BandChip(
                    if (state.armed == true) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    armText,
                    if (state.armed == true) ChipTone.Crit else ChipTone.Neutral,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                BandChip(Icons.Outlined.FlightTakeoff, state.modeName ?: DASH, ChipTone.Accent, onClick = onOpenModes)

                val batteryText = state.batteryPct?.let { pct ->
                    val ttr = state.timeToReserveS?.let { " · " + formatDuration(it.toLong()) }.orEmpty()
                    "$pct%$ttr"
                } ?: DASH
                BandChip(
                    when (state.batteryLevel) {
                        BatteryBand.Unknown -> Icons.Outlined.BatteryUnknown
                        BatteryBand.Good -> Icons.Outlined.Battery5Bar
                        else -> Icons.Outlined.BatteryAlert
                    },
                    batteryText,
                    when (state.batteryLevel) {
                        BatteryBand.Unknown -> ChipTone.Neutral
                        BatteryBand.Good -> ChipTone.Good
                        BatteryBand.Low -> ChipTone.Warn
                        BatteryBand.Critical -> ChipTone.Crit
                    },
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                val gpsGood = state.gpsFix != null && state.gpsFix.ordinal >= GpsFix.Fix3D.ordinal
                BandChip(
                    if (gpsGood) Icons.Outlined.GpsFixed else Icons.Outlined.GpsOff,
                    gpsLabel(state.gpsFix) + (state.satellites?.let { " · $it" } ?: ""),
                    when {
                        state.gpsFix == null -> ChipTone.Neutral
                        gpsGood -> ChipTone.Good
                        else -> ChipTone.Warn
                    },
                )

                val linkText = when {
                    state.link == LinkHealth.None -> stringResource(R.string.cockpit_link_none)
                    state.rssiDbm != null -> "${state.rssiDbm} dBm" + (state.lqPct?.let { " · $it%" } ?: "")
                    state.lqPct != null -> "LQ ${state.lqPct}%"
                    state.linkAgeDeciS != null -> "%.1f s".format(state.linkAgeDeciS / 10f)
                    else -> DASH
                }
                BandChip(
                    Icons.Outlined.SettingsInputAntenna,
                    linkText,
                    when (state.link) {
                        LinkHealth.None -> ChipTone.Neutral
                        LinkHealth.Ok -> ChipTone.Good
                        LinkHealth.Stale -> ChipTone.Warn
                        LinkHealth.Lost -> ChipTone.Crit
                    },
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )

                val (videoText, videoTone) = when (val v = state.video) {
                    is VideoState.Live -> (stringResource(R.string.cockpit_video_live) + (v.latencyMs?.let { " · $it ms" } ?: "")) to ChipTone.Good
                    is VideoState.Frozen -> stringResource(R.string.cockpit_video_frozen) to ChipTone.Warn
                    VideoState.Connecting -> stringResource(R.string.cockpit_video_connecting) to ChipTone.Neutral
                    else -> stringResource(R.string.cockpit_video_none) to ChipTone.Neutral
                }
                BandChip(if (state.video is VideoState.Live) Icons.Outlined.Videocam else Icons.Outlined.VideocamOff, videoText, videoTone)

                when (val stick = state.stick) {
                    StickChip.NoGamepad -> Unit
                    StickChip.LinkBlocked -> BandChip(Icons.Outlined.Gamepad, stringResource(R.string.cockpit_stick_blocked), ChipTone.Neutral)
                    is StickChip.Available -> BandChip(
                        Icons.Outlined.Gamepad,
                        stringResource(if (stick.on) R.string.cockpit_stick_on else R.string.cockpit_stick_off),
                        if (stick.on) ChipTone.Accent else ChipTone.Neutral,
                        onClick = onToggleStick,
                    )
                }

                if (state.preflightTotal > 0) {
                    val ready = state.preflightPassed == state.preflightTotal
                    BandChip(
                        Icons.Outlined.Checklist,
                        stringResource(R.string.cockpit_preflight_chip, state.preflightPassed, state.preflightTotal),
                        if (ready) ChipTone.Good else ChipTone.Warn,
                        onClick = onOpenPreflight,
                    )
                }

                state.recordingStartedAtMs?.let { start ->
                    ElapsedChip(Icons.Outlined.FiberManualRecord, start, System::currentTimeMillis, ChipTone.Crit, stringResource(R.string.cockpit_rec))
                }
                state.armedSinceMs?.let { start ->
                    ElapsedChip(Icons.Outlined.Timer, start, SystemClock::elapsedRealtime, ChipTone.Neutral, stringResource(R.string.cockpit_flight_time))
                }

                IconButton(onClick = onOpenQuickSettings, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.cockpit_quick_settings))
                }
            }
            Spacer(Modifier.width(AdosSpacing.S1))
            BandChip(Icons.Outlined.Dangerous, stringResource(R.string.cockpit_kill), ChipTone.Crit, onClick = onKill)
        }
    }
}
