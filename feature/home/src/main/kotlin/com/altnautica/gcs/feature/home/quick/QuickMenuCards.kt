package com.altnautica.gcs.feature.home.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.component.node.profileIcon
import com.altnautica.gcs.core.designsystem.component.node.profileTint
import com.altnautica.gcs.core.designsystem.component.node.reachLabel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.skills.cockpit.CockpitSummary
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.feature.home.FleetCard
import com.altnautica.gcs.feature.home.R
import com.altnautica.gcs.feature.home.livenessLabel

/** The mission on the open cockpit's vehicle: its state and waypoint. Opens the planner. */
@Composable
internal fun MissionStatusCard(cockpit: CockpitSummary?, onClick: () -> Unit) {
    val mission = cockpit?.mission
    val active = mission != null && mission.state != MissionState.None
    StatusCard(
        title = stringResource(R.string.home_quick_mission),
        icon = Icons.Outlined.Route,
        iconTint = if (active) AdosTheme.colors.accentPrimary else AdosTheme.colors.fgTertiary,
        onClick = onClick,
    ) {
        if (mission == null || mission.state == MissionState.None) {
            Line(stringResource(R.string.home_quick_mission_none))
            return@StatusCard
        }
        Line(
            stringResource(
                when (mission.state) {
                    MissionState.Loaded -> R.string.home_quick_mission_loaded
                    MissionState.Active -> R.string.home_quick_mission_active
                    MissionState.Paused -> R.string.home_quick_mission_paused
                    MissionState.Complete -> R.string.home_quick_mission_complete
                    MissionState.Returning -> R.string.home_quick_mission_returning
                    MissionState.None -> R.string.home_quick_mission_none
                },
            ),
        )
        if (mission.total > 0) Line(stringResource(R.string.home_quick_mission_wp, mission.seq, mission.total))
    }
}

private val CardWidth = 232.dp
private val CardMinHeight = 132.dp
private const val SEPARATOR = " · "

/** The selected vehicle: mode, arming, battery, link and, while its cockpit is open, preflight. */
@Composable
internal fun VehicleStatusCard(vehicle: FleetCard?, cockpit: CockpitSummary?) {
    val node = vehicle?.node
    StatusCard(
        title = node?.name ?: stringResource(R.string.home_quick_no_vehicle),
        icon = profileIcon(node?.profile ?: NodeProfile.Unknown),
        iconTint = if (node != null) profileTint(node.profile) else AdosTheme.colors.fgTertiary,
    ) {
        if (vehicle == null || node == null) return@StatusCard
        val vitals = vehicle.vitals
        val mode = vitals.modeName ?: stringResource(R.string.home_mode_unknown)
        val armed = vitals.armed?.let { stringResource(if (it) R.string.home_armed else R.string.home_disarmed) }
        Line(listOfNotNull(mode, armed).joinToString(SEPARATOR))
        val pct = vitals.batteryPct
        Line(if (pct != null) stringResource(R.string.home_battery, pct) else stringResource(R.string.home_battery_unknown))
        Line(stringResource(livenessLabel(node.liveness)) + SEPARATOR + stringResource(reachLabel(node.reaches.firstOrNull())))
        if (cockpit != null && cockpit.nodeId == node.id) {
            Line(stringResource(R.string.home_quick_preflight, cockpit.preflightDone, cockpit.preflightTotal))
        }
    }
}

/** The controller in use: name, battery and, while a cockpit is open, stick control. Opens the controller surface. */
@Composable
internal fun ControllerStatusCard(controller: ControllerInfo?, cockpit: CockpitSummary?, onClick: () -> Unit) {
    StatusCard(
        title = stringResource(R.string.home_quick_controller),
        icon = Icons.Outlined.SportsEsports,
        iconTint = if (controller != null) AdosTheme.colors.fgPrimary else AdosTheme.colors.fgTertiary,
        onClick = onClick,
    ) {
        if (controller == null) {
            Line(stringResource(R.string.home_quick_no_controller))
            return@StatusCard
        }
        Line(controller.name)
        val pct = controller.batteryPct
        val battery = if (pct != null) stringResource(R.string.home_battery, pct) else stringResource(R.string.home_quick_battery_unknown)
        val charging = if (controller.charging) SEPARATOR + stringResource(R.string.home_quick_charging) else ""
        Line(battery + charging)
        if (cockpit != null) {
            Line(stringResource(if (cockpit.stickOn) R.string.home_quick_stick_on else R.string.home_quick_stick_off))
        }
    }
}

/** Active cockpit alerts: shown only while a cockpit is open and has any. */
@Composable
internal fun AlertsStatusCard(cockpit: CockpitSummary?) {
    if (cockpit == null || cockpit.alertCount <= 0) return
    StatusCard(
        title = stringResource(R.string.home_quick_alerts),
        icon = Icons.Outlined.NotificationsActive,
        iconTint = AdosTheme.colors.statusWarning,
    ) {
        Line(pluralStringResource(R.plurals.home_quick_alert_count, cockpit.alertCount, cockpit.alertCount))
        cockpit.topAlert?.let { Line(it, maxLines = 2) }
    }
}

/** Status card sized like a large launch card; focusable and clickable only with [onClick]. */
@Composable
private fun StatusCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AdosTheme.colors
    val shape = RoundedCornerShape(AdosRadius.Xl)
    val base = Modifier
        .width(CardWidth)
        .heightIn(min = CardMinHeight)
    val interactive = if (onClick != null) base.consoleFocus(shape, scaleOnFocus = true) else base
    val clipped = interactive.clip(shape).background(colors.bgRaised)
    Column(
        modifier = (if (onClick != null) clipped.clickable(role = Role.Button, onClick = onClick) else clipped)
            .semantics(mergeDescendants = true) {}
            .padding(AdosSpacing.S3),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(AdosSize.IconMd))
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.fgPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

@Composable
private fun Line(text: String, maxLines: Int = 1) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = AdosTheme.colors.fgSecondary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
