package com.altnautica.gcs.feature.fleet.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.feature.fleet.R

/** CS-2 reach badge: `DIRECT` / `LAN` / `VIA GROUND` / `CLOUD` / `PHONE RADIO`, or why the node is blocked. */
@Composable
fun ReachBadge(reach: Reach?, modifier: Modifier = Modifier) {
    val level = when (reach) {
        null, is Reach.Blocked -> StatusLevel.Neutral
        Reach.CloudQueue -> StatusLevel.Warning
        else -> StatusLevel.Info
    }
    StatusChip(level = level, label = stringResource(reachLabel(reach)), icon = reachIcon(reach), modifier = modifier)
}

fun reachLabel(reach: Reach?): Int = when (reach) {
    null -> R.string.reach_none
    Reach.DirectFc -> R.string.reach_direct
    Reach.Lan -> R.string.reach_lan
    is Reach.ViaGround -> R.string.reach_via_ground
    Reach.CloudLive -> R.string.reach_cloud
    Reach.CloudQueue -> R.string.reach_cloud_queue
    Reach.PhoneRadio -> R.string.reach_phone_radio
    is Reach.Blocked -> when (reach.reason) {
        BlockReason.NotPaired -> R.string.reach_blocked_not_paired
        BlockReason.CloudDisabled -> R.string.reach_blocked_cloud_disabled
        BlockReason.SignedOut -> R.string.reach_blocked_signed_out
        BlockReason.NoGrant -> R.string.reach_blocked_no_grant
        BlockReason.GrantExpired -> R.string.reach_blocked_grant_expired
        BlockReason.Offline -> R.string.reach_blocked_offline
        BlockReason.ReceiveOnly -> R.string.reach_blocked_receive_only
    }
}

private fun reachIcon(reach: Reach?): ImageVector = when (reach) {
    null -> Icons.Outlined.LinkOff
    Reach.DirectFc -> Icons.Outlined.Usb
    Reach.Lan -> Icons.Outlined.Wifi
    is Reach.ViaGround -> Icons.Outlined.CellTower
    Reach.CloudLive -> Icons.Outlined.Cloud
    Reach.CloudQueue -> Icons.Outlined.CloudQueue
    Reach.PhoneRadio -> Icons.Outlined.SettingsInputAntenna
    is Reach.Blocked -> Icons.Outlined.Lock
}

@Composable
fun LivenessChip(liveness: Liveness, modifier: Modifier = Modifier) {
    val (level, label) = when (liveness) {
        Liveness.Live -> StatusLevel.Success to R.string.liveness_live
        Liveness.Stale -> StatusLevel.Warning to R.string.liveness_stale
        Liveness.Offline -> StatusLevel.Neutral to R.string.liveness_offline
    }
    StatusChip(level = level, label = stringResource(label), modifier = modifier)
}

fun profileIcon(profile: NodeProfile): ImageVector = when (profile) {
    NodeProfile.Drone -> Icons.Outlined.Flight
    NodeProfile.GroundStation -> Icons.Outlined.Router
    NodeProfile.Workstation -> Icons.Outlined.Computer
    NodeProfile.Visor -> Icons.Outlined.DocumentScanner
    NodeProfile.Unknown -> Icons.Outlined.Hub
}

fun profileLabel(profile: NodeProfile): Int = when (profile) {
    NodeProfile.Drone -> R.string.profile_drone
    NodeProfile.GroundStation -> R.string.profile_ground_station
    NodeProfile.Workstation -> R.string.profile_workstation
    NodeProfile.Visor -> R.string.profile_visor
    NodeProfile.Unknown -> R.string.profile_unknown
}

/**
 * Categorical identity hue per node profile, the same values Mission Control
 * uses for its node accents. These are identity colours, not status: a status
 * still pairs its own colour with an icon and a label. An unknown profile gets
 * the neutral secondary foreground.
 */
@Composable
fun profileTint(profile: NodeProfile): Color = when (profile) {
    NodeProfile.Drone -> NodeAccent.DRONE
    NodeProfile.GroundStation -> NodeAccent.GROUND_STATION
    NodeProfile.Workstation -> NodeAccent.WORKSTATION
    NodeProfile.Visor -> NodeAccent.VISOR
    NodeProfile.Unknown -> AdosTheme.colors.fgSecondary
}

/** Node identity accents, mirrored from Mission Control's `--node-accent-*` values. */
object NodeAccent {
    val DRONE = Color(0xFF3B82F6)
    val GROUND_STATION = Color(0xFFA78BFA)
    val WORKSTATION = Color(0xFF2DD4BF)
    val VISOR = Color(0xFFF9A8D4)
}
