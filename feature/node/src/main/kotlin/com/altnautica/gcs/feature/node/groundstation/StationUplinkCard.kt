package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.overlay.AdosConfirmSheet
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import java.text.DateFormat
import java.util.Date

/**
 * The box's uplink handover, as every box surface shows it: transmitting, or
 * standby with the phone holding it, since when and when the box last heard
 * it. Take back is the box operator's action; the box tells the phone first.
 */
@Composable
internal fun StationUplinkCard(uplink: StationUplink?, busy: Boolean, onTakeBack: () -> Unit) {
    NodeCard(stringResource(R.string.gs_uplink_title)) {
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        if (uplink == null) {
            StatusChip(StatusLevel.Neutral, stringResource(R.string.gs_uplink_unreported), modifier = live)
            return@NodeCard
        }
        if (!uplink.standby) {
            StatusChip(StatusLevel.Success, stringResource(R.string.gs_uplink_transmitting), modifier = live)
            return@NodeCard
        }
        val holder = uplink.holderName ?: uplink.holderDeviceId.orEmpty()
        StatusChip(StatusLevel.Info, stringResource(R.string.gs_uplink_standby, holder), modifier = live)
        uplink.sinceMs?.let {
            KeyValueRow(stringResource(R.string.gs_uplink_since), DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)))
        }
        KeyValueRow(
            stringResource(R.string.gs_uplink_last_heard),
            uplink.lastBeaconMs?.let {
                val ago = ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0).toInt()
                stringResource(R.string.gs_uplink_last_heard_value, ago)
            } ?: stringResource(R.string.gs_uplink_not_heard),
        )
        var confirm by rememberSaveable { mutableStateOf(false) }
        AdosButton(stringResource(R.string.gs_uplink_take_back), onClick = { confirm = true }, enabled = !busy, variant = AdosButtonVariant.Secondary)
        if (confirm) {
            AdosConfirmSheet(
                title = stringResource(R.string.gs_uplink_take_back_title, holder),
                message = stringResource(R.string.gs_uplink_take_back_message),
                onDismiss = { confirm = false },
            ) {
                AdosButton(
                    stringResource(R.string.gs_uplink_take_back),
                    onClick = {
                        confirm = false
                        onTakeBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
