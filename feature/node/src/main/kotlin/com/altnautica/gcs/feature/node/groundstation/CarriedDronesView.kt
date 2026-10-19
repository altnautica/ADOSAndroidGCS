package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard

/** Carried drones tab body: one card per fleet slot or primary drone, with a guarded release. */
@Composable
internal fun CarriedView(c: CarriedDrones, busy: Boolean, onOpenNode: (DeviceId) -> Unit, onRelease: (String) -> Unit) {
    var releasing by rememberSaveable { mutableStateOf<String?>(null) }
    if (c.slots.isEmpty() && c.primaryDroneId == null) {
        EmptyState(
            icon = Icons.Outlined.LinkOff,
            title = stringResource(R.string.gs_carried_empty_title),
            message = stringResource(R.string.gs_carried_empty_message),
        )
        return
    }
    val peers = c.peers.orEmpty().associateBy { it.deviceId }
    val ids = (c.slots.map { it.deviceId } + listOfNotNull(c.primaryDroneId)).distinct()
    ids.forEach { id ->
        val slot = c.slots.firstOrNull { it.deviceId == id }
        val peer = peers[id]
        NodeCard(null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onOpenNode(DeviceId(id)) },
            ) {
                Icon(Icons.Outlined.Flight, contentDescription = null, tint = AdosTheme.colors.accentPrimary)
                Column(Modifier.weight(1f).padding(start = AdosSpacing.S3)) {
                    Text(peer?.name ?: id, style = MaterialTheme.typography.titleSmall)
                    if (peer?.name != null) Text(id, style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
                }
                StatusChip(
                    level = if (peer?.statusFresh == true) StatusLevel.Success else StatusLevel.Neutral,
                    label = stringResource(if (peer?.statusFresh == true) R.string.gs_carried_fresh else R.string.gs_carried_not_heard),
                )
            }
            slot?.let { s ->
                KeyValueRow(stringResource(R.string.gs_carried_slot), s.slot.toString())
                KeyValueRow(stringResource(R.string.gs_carried_relay_credential), s.relayCredential)
                if (s.videoHero) KeyValueRow(stringResource(R.string.gs_carried_video), stringResource(R.string.gs_carried_video_primary))
                if (s.systemIdConflict) StatusChip(StatusLevel.Warning, stringResource(R.string.gs_carried_sysid_conflict))
            }
            peer?.agentVersion?.let { KeyValueRow(stringResource(R.string.gs_carried_agent), it) }
            if (slot != null) {
                AdosButton(
                    label = stringResource(R.string.gs_carried_release),
                    onClick = { releasing = id },
                    variant = AdosButtonVariant.Secondary,
                    enabled = !busy,
                )
            }
        }
    }
    releasing?.let { id ->
        AlertDialog(
            onDismissRequest = { releasing = null },
            title = { Text(stringResource(R.string.gs_carried_release_title)) },
            text = { Text(stringResource(R.string.gs_carried_release_message, id)) },
            confirmButton = {
                TextButton(onClick = {
                    onRelease(id)
                    releasing = null
                }) { Text(stringResource(R.string.gs_carried_release)) }
            },
            dismissButton = { TextButton(onClick = { releasing = null }) { Text(stringResource(R.string.node_cancel)) } },
        )
    }
}
