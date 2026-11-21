package com.altnautica.gcs.feature.cockpit.hud

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.ShareLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.link.R
import com.altnautica.gcs.core.link.follow.BeaconShare
import com.altnautica.gcs.core.link.follow.FollowBeaconController
import com.altnautica.gcs.core.link.follow.ShareBlock
import com.altnautica.gcs.feature.cockpit.chrome.ChipTone
import com.altnautica.gcs.feature.cockpit.chrome.color
import kotlin.math.roundToInt

const val BEACON_CHIP_TAG = "follow-beacon-chip"

/**
 * "Share my position for follow-me" in the follow strip, shown while a connected vehicle has
 * follow-me. Off it reads as the offer; on it reads "Sharing position (±N m)" or why it is not
 * sharing. A tap turns sharing on (asking for precise location first when needed) or off.
 */
@Composable
internal fun BeaconShareChip(beacon: FollowBeaconController, modifier: Modifier = Modifier) {
    val available by beacon.available.collectAsStateWithLifecycle()
    val share by beacon.share.collectAsStateWithLifecycle()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        beacon.permissionChanged()
    }
    if (!available) return
    val on = share != BeaconShare.Off
    BeaconShareChipView(
        share = share,
        onClick = {
            beacon.setEnabled(!on)
            if (!on) {
                request.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        },
        modifier = modifier,
    )
}

/** The chip for one [share] state; stateless. */
@Composable
internal fun BeaconShareChipView(share: BeaconShare, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = AdosTheme.colors
    val (tone, icon) = when (share) {
        BeaconShare.Off -> ChipTone.Neutral to Icons.Outlined.ShareLocation
        is BeaconShare.Sharing -> ChipTone.Good to Icons.Outlined.ShareLocation
        is BeaconShare.Blocked -> ChipTone.Warn to Icons.Outlined.LocationOff
    }
    val label = beaconText(share)
    val state = stringResource(if (share == BeaconShare.Off) R.string.follow_beacon_state_off else R.string.follow_beacon_state_on)
    Surface(
        onClick = onClick,
        modifier = modifier.testTag(BEACON_CHIP_TAG).semantics {
            role = Role.Switch
            stateDescription = state
        },
        shape = RoundedCornerShape(8.dp),
        color = c.hudGlassStrong,
        contentColor = c.hudInk,
        border = BorderStroke(1.dp, c.hudHair),
    ) {
        Row(
            Modifier.padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S1),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tone.color(), modifier = Modifier.size(14.dp))
            Text(label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun beaconText(share: BeaconShare): String = when (share) {
    BeaconShare.Off -> stringResource(R.string.follow_beacon_share)
    is BeaconShare.Sharing -> stringResource(R.string.follow_beacon_sharing, share.accuracyM.roundToInt().coerceAtLeast(1))
    is BeaconShare.Blocked -> stringResource(
        when (share.reason) {
            ShareBlock.NoVehicle -> R.string.follow_beacon_blocked_no_vehicle
            ShareBlock.NoFollow -> R.string.follow_beacon_blocked_no_follow
            ShareBlock.NoPermission -> R.string.follow_beacon_blocked_no_permission
            ShareBlock.Background -> R.string.follow_beacon_blocked_background
            ShareBlock.NoFix -> R.string.follow_beacon_blocked_no_fix
            ShareBlock.NotSent -> R.string.follow_beacon_blocked_not_sent
            ShareBlock.NotConfirmed -> R.string.follow_beacon_blocked_not_confirmed
        },
    )
}
