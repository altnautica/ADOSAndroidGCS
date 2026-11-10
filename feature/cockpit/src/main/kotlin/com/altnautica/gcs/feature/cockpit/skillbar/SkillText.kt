package com.altnautica.gcs.feature.cockpit.skillbar

import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.knowsRefusal
import com.altnautica.gcs.core.skills.hud.refusalText
import com.altnautica.gcs.feature.cockpit.flow.LocalCockpitStrings
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Dangerous
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.FrontHand
import androidx.compose.material.icons.outlined.Height
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.core.skills.builtinLabelRes
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy

/** Material Symbols Outlined for the skill icon names; extensions fall back to a generic glyph. */
fun skillIcon(name: String): ImageVector = when (name) {
    "power" -> Icons.Outlined.Power
    "power_off" -> Icons.Outlined.PowerOff
    "flight_takeoff" -> Icons.Outlined.FlightTakeoff
    "flight_land" -> Icons.Outlined.FlightLand
    "home" -> Icons.Outlined.Home
    "pause" -> Icons.Outlined.Pause
    "play_arrow" -> Icons.Outlined.PlayArrow
    "cancel" -> Icons.Outlined.Cancel
    "dangerous" -> Icons.Outlined.Dangerous
    "my_location" -> Icons.Outlined.MyLocation
    "front_hand" -> Icons.Outlined.FrontHand
    "height" -> Icons.Outlined.Height
    "center_focus_strong" -> Icons.Outlined.CenterFocusStrong
    "navigation" -> Icons.Outlined.Navigation
    "route" -> Icons.Outlined.Route
    "tune" -> Icons.Outlined.Tune
    "near_me" -> Icons.Outlined.NearMe
    "videocam", "camera" -> Icons.Outlined.Videocam
    "visibility", "eye" -> Icons.Outlined.Visibility
    else -> Icons.Outlined.Extension
}

private val CONFIRM_TITLES = mapOf(
    "skills.arm.confirm.title" to "confirm.arm.title",
    "skills.disarm.confirm.title" to "confirm.disarm.title",
    "skills.takeoff.confirm.title" to "setup.action.take_off",
    "skills.land.confirm.title" to "setup.action.land",
    "skills.rth.confirm.title" to "flow.launch.when_done.rth",
    "skills.resume.confirm.title" to "confirm.resume.title",
    "skills.abort.confirm.title" to "setup.action.abort",
    "skills.kill.confirm.title" to "confirm.kill.title",
    "skills.modeChange.confirm.title" to "confirm.mode.title",
    "skills.flyHere.confirm.title" to "map.fly_here",
    "skills.ekfSourceSet.confirm.title" to "nav_source.confirm.title",
)

private val CONFIRM_MESSAGES = mapOf(
    "skills.arm.confirm.message" to "confirm.arm.message",
    "skills.disarm.confirm.message" to "confirm.disarm.message",
    "skills.takeoff.confirm.message" to "confirm.takeoff.message",
    "skills.land.confirm.message" to "confirm.land.message",
    "skills.rth.confirm.message" to "confirm.rth.message",
    "skills.resume.confirm.message" to "confirm.resume.message",
    "skills.abort.confirm.message" to "confirm.abort.message",
    "skills.kill.confirm.message" to "confirm.kill.message",
    "skills.modeChange.confirm.message" to "confirm.mode.message",
    "skills.flyHere.confirm.message" to "confirm.fly_here.message",
    "skills.ekfSourceSet.confirm.message" to "nav_source.confirm.message",
)

/** Spec text keys of the known reasons; a Fly Here refusal reads "Fly Here refused: <reason>". */
private val REASON_KEYS = mapOf(
    SkillReason.NO_FC_LINK to "refusal.no_fc_link",
    SkillReason.RECEIVE_ONLY to "refusal.receive_only",
    SkillReason.NOT_ARMED to "refusal.not_armed",
    SkillReason.MODE_UNAVAILABLE to "refusal.mode_unavailable",
    SkillReason.NO_AUTONOMOUS_NAV to "refusal.no_autonomous_nav",
    SkillReason.NO_CHARGES to "refusal.no_charges",
    SkillReason.COMMAND_REJECTED to "refusal.command_rejected",
    SkillReason.COMMAND_TIMEOUT to "refusal.no_response",
    SkillReason.COMMAND_UNSUPPORTED to "refusal.unknown_action",
    SkillReason.COMMAND_DENIED to "refusal.not_sent",
    SkillReason.COMMAND_QUEUED to "refusal.queued",
    SkillReason.COMMAND_FAILED to "mav_result.FAILED",
    SkillReason.ALREADY_ARMED to "refusal.already_armed",
    SkillReason.ALREADY_DISARMED to "refusal.already_disarmed",
    SkillReason.LANDED_UNKNOWN to "refusal.landed_unknown",
    SkillReason.STATE_ERROR to "refusal.state_error",
    SkillReason.FLY_HERE_TARGET to "refusal.fly_here_target",
    CockpitAutonomy.REASON_NO_TARGET to "refusal.no_target",
    SkillReason.EKF_SOURCE_SET to "nav_source.bad_set",
    SkillReason.PILOT_UNAVAILABLE to "cockpit.pilot.unavailable",
    SkillReason.VEHICLE_CLASS to "refusal.vehicle_class",
    SkillReason.SUPERSEDED to "refusal.superseded",
)

private val FLY_HERE_REASON_KEYS = mapOf(
    SkillReason.GOTO_BAD_ARGS to "refusal.bad_args",
    SkillReason.GOTO_NOT_ARMED to "refusal.not_armed",
    SkillReason.GOTO_LANDED to "refusal.landed",
    SkillReason.GOTO_TUNING to "refusal.tuning",
    SkillReason.GOTO_NO_HOME to "refusal.no_home",
    SkillReason.GOTO_LAUNCHING to "refusal.launching",
    SkillReason.GOTO_CONTROL_MODE to "refusal.control_mode",
    SkillReason.GOTO_FAILSAFE to "refusal.failsafe",
)

/** Built-in labels are localized; an extension's label is shown as the extension provides it. */
@Composable
fun skillLabel(skill: Skill): String = skill.builtinLabelRes()?.let { stringResource(it) } ?: skill.labelKey

/** The text of a known reason key from the spec strings, or null for extension text. */
fun reasonKeyText(strings: SetupStringsSpec, reasonKey: String): String? =
    REASON_KEYS[reasonKey]?.let(strings::format)
        ?: FLY_HERE_REASON_KEYS[reasonKey]?.let { strings.format("refusal.fly_here_prefix", mapOf("reason" to strings.format(it).replaceFirstChar(Char::lowercase))) }

/**
 * A known reason key reads through the spec strings; a refusal code the pilot or the gates
 * answer with (failsafe, not_ready:<item>, …) through the spec's refusal texts; any other
 * reason is extension text shown verbatim.
 */
fun reasonTextOf(strings: SetupStringsSpec, reasonKey: String): String {
    reasonKeyText(strings, reasonKey)?.let { return it }
    val spec = SpecText(strings)
    return if (spec.knowsRefusal(reasonKey)) spec.refusalText(reasonKey) else reasonKey
}

@Composable
fun reasonText(reasonKey: String): String =
    reasonTextOf(LocalCockpitStrings.current, reasonKey)

@Composable
fun confirmTitle(key: String, fallback: String): String = CONFIRM_TITLES[key]?.let { specText(it) } ?: fallback

@Composable
fun confirmMessage(key: String): String = CONFIRM_MESSAGES[key]?.let { specText(it) } ?: key
