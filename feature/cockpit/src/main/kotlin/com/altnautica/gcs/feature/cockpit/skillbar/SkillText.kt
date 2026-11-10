package com.altnautica.gcs.feature.cockpit.skillbar

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
import com.altnautica.gcs.feature.cockpit.R
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
    "skills.arm.confirm.title" to R.string.cockpit_confirm_arm_title,
    "skills.disarm.confirm.title" to R.string.cockpit_confirm_disarm_title,
    "skills.takeoff.confirm.title" to R.string.cockpit_confirm_takeoff_title,
    "skills.land.confirm.title" to R.string.cockpit_confirm_land_title,
    "skills.rth.confirm.title" to R.string.cockpit_confirm_rth_title,
    "skills.resume.confirm.title" to R.string.cockpit_confirm_resume_title,
    "skills.abort.confirm.title" to R.string.cockpit_confirm_abort_title,
    "skills.kill.confirm.title" to R.string.cockpit_confirm_kill_title,
    "skills.modeChange.confirm.title" to R.string.cockpit_confirm_mode_title,
    "skills.flyHere.confirm.title" to R.string.cockpit_confirm_fly_here_title,
    "skills.ekfSourceSet.confirm.title" to R.string.cockpit_confirm_ekf_set_title,
)

private val CONFIRM_MESSAGES = mapOf(
    "skills.arm.confirm.message" to R.string.cockpit_confirm_arm_message,
    "skills.disarm.confirm.message" to R.string.cockpit_confirm_disarm_message,
    "skills.takeoff.confirm.message" to R.string.cockpit_confirm_takeoff_message,
    "skills.land.confirm.message" to R.string.cockpit_confirm_land_message,
    "skills.rth.confirm.message" to R.string.cockpit_confirm_rth_message,
    "skills.resume.confirm.message" to R.string.cockpit_confirm_resume_message,
    "skills.abort.confirm.message" to R.string.cockpit_confirm_abort_message,
    "skills.kill.confirm.message" to R.string.cockpit_confirm_kill_message,
    "skills.modeChange.confirm.message" to R.string.cockpit_confirm_mode_message,
    "skills.flyHere.confirm.message" to R.string.cockpit_confirm_fly_here_message,
    "skills.ekfSourceSet.confirm.message" to R.string.cockpit_confirm_ekf_set_message,
)

private val REASONS = mapOf(
    SkillReason.NO_FC_LINK to R.string.cockpit_reason_no_fc_link,
    SkillReason.RECEIVE_ONLY to R.string.cockpit_reason_receive_only,
    SkillReason.ALREADY_ARMED to R.string.cockpit_reason_already_armed,
    SkillReason.NOT_ARMED to R.string.cockpit_reason_not_armed,
    SkillReason.ALREADY_DISARMED to R.string.cockpit_reason_already_disarmed,
    SkillReason.MODE_UNAVAILABLE to R.string.cockpit_reason_mode_unavailable,
    SkillReason.NO_AUTONOMOUS_NAV to R.string.cockpit_reason_no_autonomous_nav,
    SkillReason.NO_CHARGES to R.string.cockpit_reason_no_charges,
    SkillReason.COMMAND_REJECTED to R.string.cockpit_reason_command_rejected,
    SkillReason.COMMAND_TIMEOUT to R.string.cockpit_reason_command_timeout,
    SkillReason.COMMAND_UNSUPPORTED to R.string.cockpit_reason_command_unsupported,
    SkillReason.COMMAND_DENIED to R.string.cockpit_reason_command_denied,
    SkillReason.COMMAND_QUEUED to R.string.cockpit_reason_command_queued,
    SkillReason.COMMAND_FAILED to R.string.cockpit_reason_command_failed,
    SkillReason.STATE_ERROR to R.string.cockpit_reason_state_error,
    SkillReason.FLY_HERE_TARGET to R.string.cockpit_reason_fly_here_target,
    CockpitAutonomy.REASON_NO_TARGET to R.string.cockpit_reason_no_target,
    SkillReason.EKF_SOURCE_SET to R.string.cockpit_reason_ekf_set,
    SkillReason.PILOT_UNAVAILABLE to R.string.cockpit_pilot_unavailable,
    SkillReason.VEHICLE_CLASS to R.string.cockpit_reason_vehicle_class,
    SkillReason.SUPERSEDED to R.string.cockpit_reason_superseded,
    SkillReason.GOTO_BAD_ARGS to R.string.cockpit_reason_goto_bad_args,
    SkillReason.GOTO_NOT_ARMED to R.string.cockpit_reason_goto_not_armed,
    SkillReason.GOTO_LANDED to R.string.cockpit_reason_goto_landed,
    SkillReason.GOTO_TUNING to R.string.cockpit_reason_goto_tuning,
    SkillReason.GOTO_NO_HOME to R.string.cockpit_reason_goto_no_home,
    SkillReason.GOTO_LAUNCHING to R.string.cockpit_reason_goto_launching,
    SkillReason.GOTO_CONTROL_MODE to R.string.cockpit_reason_goto_control_mode,
    SkillReason.GOTO_FAILSAFE to R.string.cockpit_reason_goto_failsafe,
)

/** Built-in labels are localized; an extension's label is shown as the extension provides it. */
@Composable
fun skillLabel(skill: Skill): String = skill.builtinLabelRes()?.let { stringResource(it) } ?: skill.labelKey

/** String resource of a known reason key, or null for extension text. */
fun reasonRes(reasonKey: String): Int? = REASONS[reasonKey]

/** A known reason key is localized; any other reason is extension text shown verbatim. */
@Composable
fun reasonText(reasonKey: String): String = REASONS[reasonKey]?.let { stringResource(it) } ?: reasonKey

@Composable
fun confirmTitle(key: String, fallback: String): String = CONFIRM_TITLES[key]?.let { stringResource(it) } ?: fallback

@Composable
fun confirmMessage(key: String): String = CONFIRM_MESSAGES[key]?.let { stringResource(it) } ?: key
