package com.altnautica.gcs.feature.cockpit.flow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.InputSession
import com.altnautica.gcs.core.model.PilotBanner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.LaunchSequence
import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.launch.StepStrip
import com.altnautica.gcs.core.skills.hud.ReadoutFormat
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Hold that takes the stick session over from another surface. */
const val TAKE_OVER_HOLD_MS = 1_500

/**
 * The flow announcements the cockpit shows over the video, derived from the pilot state:
 * the handover banner, the centre-sticks gate ring, another surface holding the sticks,
 * the link-loss countdown and the low-battery return countdown. It holds only what the
 * screen shows: the countdowns in the whole seconds they read and the gate as pending (its
 * ring reads the progress itself, on the beat), so a pilot frame that changes nothing on
 * screen leaves it equal and redraws nothing.
 */
data class FlowUi(
    val banner: PilotBanner? = null,
    /** The centre-sticks gate is pending while this surface streams: its ring shows. */
    val gatePending: Boolean = false,
    /** Another surface holds the stick session. */
    val sticksWith: InputSession? = null,
    /** Seconds until the link-loss action, rounded up to the whole second the banner reads. */
    val linkLossInS: Float? = null,
    /** Seconds until the low-battery return, rounded up to the whole second the banner reads. */
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
                gatePending = pilot.gate?.pending == true && streaming,
                sticksWith = session?.takeIf { it.src != ownSrc },
                linkLossInS = pilot.linkLossInS?.let(::shownSeconds),
                rthCountdownS = pilot.rthCountdownS?.let(::shownSeconds),
                launch = pilot.sequence?.takeIf { it.step != LaunchStep.Done },
            )
        }

        /** A countdown as the whole second it reads (rounded up, never below zero). */
        internal fun shownSeconds(s: Float): Float = ceil(s.toDouble()).toFloat().coerceAtLeast(0f)
    }
}

/** The banner text for [banner] from the spec strings ([bannerLine]). */
@Composable
fun bannerText(banner: PilotBanner): String = bannerLine(LocalCockpitStrings.current, banner)

/**
 * The banner text for [banner] through the shared [SpecText]: its `source`, `action` and
 * `cause` arguments read as their spec labels; a value the spec does not name stays as sent.
 */
fun bannerLine(strings: SetupStringsSpec, banner: PilotBanner): String = SpecText(strings).text(banner.textKey, banner.args)

@Composable
private fun BannerShell(icon: ImageVector, tint: Color, tag: String, content: @Composable RowScope.() -> Unit) {
    // A chrome panel: the blurred video behind the spec tint, a hairline border.
    ChromePanel(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            content()
        }
    }
}

/** The transient full-width handover and status banner. */
@Composable
fun HandoverBanner(banner: PilotBanner, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.SwapHoriz, AdosTheme.colors.accentPrimary, "flow-banner") {
            Text(bannerText(banner), color = LocalChrome.current.text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/**
 * "Centre sticks to take control" with a progress ring that fills while the sticks rest
 * centred. [progress] is read while drawing, so a moving ring redraws without recomposing.
 */
@Composable
fun GateRing(progress: () -> Float, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    val chrome = LocalChrome.current
    ChromePanel(modifier.testTag("flow-gate")) {
        Column(
            Modifier.padding(AdosSpacing.S4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            val track = colors.borderStrong
            val fill = colors.accentPrimary
            Canvas(Modifier.size(64.dp)) {
                val stroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                drawArc(track, 0f, 360f, useCenter = false, style = stroke)
                drawArc(fill, -90f, 360f * progress().coerceIn(0f, 1f), useCenter = false, style = stroke)
            }
            Text(specText("flow.gate.centre_sticks"), color = chrome.text, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Another surface flies the sticks; a hold takes the session over. */
@Composable
fun SticksWithBanner(session: InputSession, onTakeOver: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.Gamepad, AdosTheme.colors.statusWarning, "flow-sticks-with") {
            Text(
                specText("flow.sticks_with", "label" to session.label.ifBlank { session.src }),
                color = LocalChrome.current.text,
                modifier = Modifier.weight(1f),
            )
            HoldToConfirmButton(specText("flow.sticks_take_over"), TAKE_OVER_HOLD_MS, onTakeOver, modifier = Modifier.widthIn(min = 160.dp))
        }
    }
}

/** The link-loss countdown: [text] names the action the pilot takes and the seconds left (`linkLossLine`). */
@Composable
fun LinkLossBanner(text: String, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.LinkOff, AdosTheme.colors.statusError, "flow-link-loss") {
            Text(
                text,
                color = LocalChrome.current.text,
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
                    specText("flow.low_battery.title", "in_s" to lowBatteryCountdown(inS).toString()),
                    color = LocalChrome.current.text,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(specText("flow.low_battery.cancel_warning"), color = LocalChrome.current.textDim, style = MaterialTheme.typography.bodySmall)
            }
            AdosButton(specText("flow.low_battery.cancel"), onClick = onCancel, modifier = Modifier.testTag("flow-low-battery-cancel"))
        }
    }
}

/** Whole seconds the return countdown shows, through the shared readout ([ReadoutFormat.countdownS]). */
internal fun lowBatteryCountdown(inS: Float): Long = ReadoutFormat.countdownS((inS * 1000f).roundToLong())

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
