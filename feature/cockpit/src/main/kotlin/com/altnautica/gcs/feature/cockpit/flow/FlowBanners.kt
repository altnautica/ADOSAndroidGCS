package com.altnautica.gcs.feature.cockpit.flow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Gamepad
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.HoldToConfirmButton
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.InputSession
import com.altnautica.gcs.core.model.PilotBanner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.LaunchSequence
import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.feature.cockpit.launch.StepStrip

/** Hold that takes the stick session over from another surface. */
const val TAKE_OVER_HOLD_MS = 1_500

/**
 * The flow announcements the cockpit shows over the video, derived from the pilot state:
 * the handover banner, the centre-sticks gate ring, another surface holding the sticks,
 * the link-loss countdown and the low-battery return countdown.
 */
data class FlowUi(
    val banner: PilotBanner? = null,
    val gateProgress: Float? = null,
    /** Another surface holds the stick session. */
    val sticksWith: InputSession? = null,
    val linkLossInS: Float? = null,
    val rthCountdownS: Float? = null,
    /** A launch sequence running (or refused): its steps and Abort stay on screen until it is done. */
    val launch: LaunchSequence? = null,
) {
    companion object {
        /**
         * The flow state for [pilot]; [ownSrc] is this surface's input source name and
         * [nowMs] vehicle-agnostic local time. A banner shows while the pilot keeps it.
         */
        fun of(pilot: PilotState?, ownSrc: String, streaming: Boolean): FlowUi {
            if (pilot == null) return FlowUi()
            val session = pilot.inputSession
            return FlowUi(
                banner = pilot.banner,
                gateProgress = pilot.gate?.takeIf { it.pending && streaming }?.progress,
                sticksWith = session?.takeIf { it.src != ownSrc },
                linkLossInS = pilot.linkLossInS,
                rthCountdownS = pilot.rthCountdownS,
                launch = pilot.sequence?.takeIf { it.step != LaunchStep.Done },
            )
        }
    }
}

/** The banner text for [banner] from the spec strings; the `source` argument is resolved to its label. */
@Composable
fun bannerText(banner: PilotBanner): String {
    val args = banner.args.map { (k, v) ->
        k to if (k == "source") specText("pilot.banner.source.$v") else v
    }
    return specText(banner.textKey, *args.toTypedArray())
}

@Composable
private fun BannerShell(icon: ImageVector, tint: Color, tag: String, content: @Composable RowScope.() -> Unit) {
    val colors = AdosTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.overlayPanel, RoundedCornerShape(AdosRadius.Lg))
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S3)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        content()
    }
}

/** The transient full-width handover and status banner. */
@Composable
fun HandoverBanner(banner: PilotBanner, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.SwapHoriz, AdosTheme.colors.accentPrimary, "flow-banner") {
            Text(bannerText(banner), color = AdosTheme.colors.fgPrimary, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** "Centre sticks to take control" with a progress ring that fills while the sticks rest centred. */
@Composable
fun GateRing(progress: Float, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    Column(
        modifier
            .background(colors.overlayPanel, RoundedCornerShape(AdosRadius.Xl))
            .padding(AdosSpacing.S4)
            .testTag("flow-gate"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) {
        val track = colors.borderStrong
        val fill = colors.accentPrimary
        Canvas(Modifier.size(64.dp)) {
            val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, useCenter = false, style = stroke)
            drawArc(fill, -90f, 360f * progress.coerceIn(0f, 1f), useCenter = false, style = stroke)
        }
        Text(specText("flow.gate.centre_sticks"), color = colors.fgPrimary, style = MaterialTheme.typography.titleSmall)
    }
}

/** Another surface flies the sticks; a hold takes the session over. */
@Composable
fun SticksWithBanner(session: InputSession, onTakeOver: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.Gamepad, AdosTheme.colors.statusWarning, "flow-sticks-with") {
            Text(
                specText("flow.sticks_with", "label" to session.label.ifBlank { session.src }),
                color = AdosTheme.colors.fgPrimary,
                modifier = Modifier.weight(1f),
            )
            HoldToConfirmButton(specText("flow.sticks_take_over"), TAKE_OVER_HOLD_MS, onTakeOver, modifier = Modifier.widthIn(min = 160.dp))
        }
    }
}

/** The link-loss countdown: the action the pilot takes and the seconds left. */
@Composable
fun LinkLossBanner(inS: Float, action: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.LinkOff, AdosTheme.colors.statusError, "flow-link-loss") {
            Text(
                specText("flow.link_loss.countdown", "action" to action, "in_s" to countdownSeconds(inS).toString()),
                color = AdosTheme.colors.fgPrimary,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/** The cancellable low-battery return countdown. */
@Composable
fun LowBatteryBanner(inS: Float, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.BatteryAlert, AdosTheme.colors.statusError, "flow-low-battery") {
            Column(Modifier.weight(1f)) {
                Text(
                    specText("flow.low_battery.title", "in_s" to countdownSeconds(inS).toString()),
                    color = AdosTheme.colors.fgPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(specText("flow.low_battery.cancel_warning"), color = AdosTheme.colors.fgSecondary, style = MaterialTheme.typography.bodySmall)
            }
            AdosButton(specText("flow.low_battery.cancel"), onClick = onCancel, modifier = Modifier.testTag("flow-low-battery-cancel"))
        }
    }
}

/** Whole seconds a countdown shows: rounded up, so it reads 0 only when the action fires (as on every surface). */
fun countdownSeconds(inS: Float): Int = kotlin.math.ceil(inS.toDouble()).toInt().coerceAtLeast(0)

/**
 * The launch sequence while it runs: its steps, the current one marked, and Abort, which
 * pauses the pilot (a hold in the air, a disarm on the ground). Shown in the top-centre
 * stack, so the steps and Abort stay after the Launch sheet and its gate close.
 */
@Composable
fun LaunchProgressBanner(sequence: LaunchSequence, onAbort: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.FlightTakeoff, AdosTheme.colors.accentPrimary, "flow-launch") {
            StepStrip(sequence, Modifier.weight(1f))
            if (sequence.error == null) {
                AdosButton(specText("flow.launch.abort"), onClick = onAbort, modifier = Modifier.testTag("flow-launch-abort"))
            }
        }
    }
}
