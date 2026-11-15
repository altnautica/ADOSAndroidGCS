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
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.WrongLocation
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
import com.altnautica.gcs.core.model.MissionStall
import com.altnautica.gcs.core.model.MissionStallKind
import com.altnautica.gcs.core.model.PilotAction
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
import com.altnautica.gcs.core.skills.hud.RouteCheckReport
import com.altnautica.gcs.core.skills.hud.RouteCheckSeverity
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Hold that takes the stick session over from another surface. */
const val TAKE_OVER_HOLD_MS = 1_500

/**
 * The flow announcements the cockpit shows over the video, derived from the pilot state:
 * the handover banner, the centre-sticks gate ring, another surface holding the sticks,
 * the link-loss countdown and the low-battery countdown. It holds only what the
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
    /** Seconds until the low-battery action, rounded up to the whole second the banner reads. */
    val rthCountdownS: Float? = null,
    /** What the low-battery countdown ends in: `land` or `rth` (null reads as a return home). */
    val lowBatteryAction: String? = null,
    /** A launch sequence running (or refused): its steps and Abort stay on screen until it is done. */
    val launch: LaunchSequence? = null,
    /** No progress on the mission or the return: the persistent stall overlay. */
    val stall: StallUi? = null,
) {
    companion object {
        /**
         * The flow state for [pilot]; [ownSrc] is this surface's input source name and
         * [nowMs] vehicle-agnostic local time. A banner shows while the pilot keeps it.
         */
        fun of(pilot: PilotState?, ownSrc: String, streaming: Boolean): FlowUi {
            if (pilot == null) return FlowUi()
            val session = pilot.inputSession
            val stall = pilot.missionStall?.let(StallUi::of)
            return FlowUi(
                banner = pilot.banner?.takeUnless { stallShadowsBanner(it.textKey, pilot.missionStall) },
                gatePending = pilot.gate?.pending == true && streaming,
                sticksWith = session?.takeIf { it.src != ownSrc },
                linkLossInS = pilot.linkLossInS?.let(::shownSeconds),
                rthCountdownS = pilot.rthCountdownS?.let(::shownSeconds),
                lowBatteryAction = pilot.lowBatteryAction,
                launch = pilot.sequence?.takeIf { it.step != LaunchStep.Done },
                stall = stall,
            )
        }

        /** A countdown as the whole second it reads (rounded up, never below zero). */
        internal fun shownSeconds(s: Float): Float = ceil(s.toDouble()).toFloat().coerceAtLeast(0f)
    }
}

/** The pilot banners a shown stall overlay repeats: the overlay carries the live countdown. */
private val STALL_BANNER_KEYS = setOf("pilot.banner.mission_stalled", "pilot.banner.mission_stalled_hold", "pilot.banner.return_blocked")

/**
 * Whether the banner [textKey] hides behind the stall overlay (the shared `stallShadowsBanner`):
 * true for the stall's own banners while [stall] shows. Any other banner (a route too tight
 * for the confined profile, a skipped waypoint) shows beside the overlay.
 */
fun stallShadowsBanner(textKey: String?, stall: MissionStall?): Boolean = stall != null && textKey != null && textKey in STALL_BANNER_KEYS

/** One stall overlay button: its spec label and the pilot action it sends. */
enum class StallAction(val labelKey: String, val action: String, val tag: String) {
    Skip("flow.mission_stall.skip", PilotAction.MISSION_SKIP, "flow-stall-skip"),
    Return("flow.mission_stall.return", PilotAction.RTH, "flow-stall-return"),
    Land("flow.mission_stall.land", PilotAction.LAND, "flow-stall-land"),
}

/**
 * The stall overlay as it reads: the waypoint label, the no-progress window in whole seconds,
 * and the seconds to the automatic action rounded up ([inS] null: the pilot holds for the operator).
 */
data class StallUi(val kind: MissionStallKind, val wp: String, val windowS: Long, val inS: Long?) {
    /** A mission stall offers skip, return and land; a blocked return offers land (or flying by stick). */
    val actions: List<StallAction>
        get() = when (kind) {
            MissionStallKind.Mission -> listOf(StallAction.Skip, StallAction.Return, StallAction.Land)
            MissionStallKind.Return -> listOf(StallAction.Land)
        }

    /** The overlay title from the spec strings. */
    fun title(strings: SetupStringsSpec): String = when (kind) {
        MissionStallKind.Mission -> {
            val args = mapOf("wp" to wp, "window_s" to windowS.toString())
            if (inS == null) {
                strings.format("flow.mission_stall.title_hold", args)
            } else {
                strings.format("flow.mission_stall.title", args + ("in_s" to inS.toString()))
            }
        }
        MissionStallKind.Return ->
            if (inS == null) strings.format("pilot.hold_reason.return-blocked") else strings.format("flow.return_blocked.title", mapOf("in_s" to inS.toString()))
    }

    companion object {
        fun of(stall: MissionStall) = StallUi(
            kind = stall.kind,
            wp = stall.wpLabel ?: stall.seq?.toString().orEmpty(),
            windowS = stall.windowS.roundToLong(),
            inS = stall.skipInS?.let { FlowUi.shownSeconds(it).toLong() },
        )
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

/** The cancellable low-battery countdown: a landing or a return home ([lowBatteryLine]). */
@Composable
fun LowBatteryBanner(inS: Float, action: String?, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.BatteryAlert, AdosTheme.colors.statusError, "flow-low-battery") {
            Column(Modifier.weight(1f)) {
                Text(
                    lowBatteryLine(LocalCockpitStrings.current, inS, action),
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
 * The low-battery countdown's title: `flow.low_battery.title_land` when the pilot will land
 * ([action] `land`), `flow.low_battery.title` (a return home) otherwise.
 */
fun lowBatteryLine(strings: SetupStringsSpec, inS: Float, action: String?): String = strings.format(
    if (action == "land") "flow.low_battery.title_land" else "flow.low_battery.title",
    mapOf("in_s" to lowBatteryCountdown(inS).toString()),
)

/**
 * The persistent stall overlay: no progress to the waypoint (or home), the countdown to the
 * automatic skip or landing, and the operator's choices, each sent to the pilot as [onAction].
 */
@Composable
fun MissionStallBanner(stall: StallUi, onAction: (StallAction) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.WrongLocation, AdosTheme.colors.statusError, "flow-mission-stall") {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                Text(stall.title(LocalCockpitStrings.current), color = LocalChrome.current.text, style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    for (a in stall.actions) {
                        AdosButton(specText(a.labelKey), onClick = { onAction(a) }, modifier = Modifier.testTag(a.tag))
                    }
                }
            }
        }
    }
}

/** The tint of a route-check report: fine, allowed but slowed, refused. */
@Composable
internal fun routeCheckTint(severity: RouteCheckSeverity): Color = when (severity) {
    RouteCheckSeverity.Ok -> AdosTheme.colors.statusSuccess
    RouteCheckSeverity.Warn -> AdosTheme.colors.statusWarning
    RouteCheckSeverity.Critical -> AdosTheme.colors.statusError
}

/** The route-check report's lines: its title, the state line and, when known, where the tightest point is. */
@Composable
fun RouteCheckLines(report: RouteCheckReport, textColor: Color, dimColor: Color, modifier: Modifier = Modifier) {
    Column(modifier.testTag("route-check-${report.state.wire}")) {
        Text(report.title, color = dimColor, style = MaterialTheme.typography.labelSmall)
        Text(report.line, color = textColor, style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("route-check-line"))
        report.pinchAt?.let { Text(it, color = dimColor, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("route-check-pinch")) }
    }
}

/** The confined profile's route check of the loaded mission, while the pilot reports one. */
@Composable
fun RouteCheckBanner(report: RouteCheckReport, modifier: Modifier = Modifier) {
    Box(modifier) {
        BannerShell(Icons.Outlined.Route, routeCheckTint(report.severity), "flow-route-check") {
            RouteCheckLines(report, LocalChrome.current.text, LocalChrome.current.textDim, Modifier.weight(1f))
        }
    }
}

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
