package com.altnautica.gcs.radio.rx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.link.fleet.RelaySecretState
import com.altnautica.gcs.radio.rx.R
import com.altnautica.gcs.radio.rx.bind.BindStage
import com.altnautica.gcs.radio.rx.bind.BindState

/** The bind actions the screen offers: open the Bind section, start a bind, cancel it. */
data class BindActions(val onBind: () -> Unit = {}, val onStart: () -> Unit = {}, val onCancel: () -> Unit = {})

/** One drone of the fleet this phone bound, as the screen lists it. */
data class FleetDroneUi(val slot: Int, val deviceId: String, val secret: RelaySecretState)

/** The fleet this phone bound over the air and is the ground of. */
data class BoundFleetUi(
    val fleetId: Int,
    val channel: Int,
    val keyFingerprint: String,
    val keyProven: Boolean,
    val drones: List<FleetDroneUi>,
)

/** The keys and fleet of a phone that bound its own drones, with the forget action. */
@Composable
internal fun BoundFleetKeys(fleet: BoundFleetUi, onForget: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    BindBody {
        StatusChip(
            if (fleet.keyProven) StatusLevel.Success else StatusLevel.Warning,
            stringResource(if (fleet.keyProven) R.string.phone_radio_fleet_key_proven else R.string.phone_radio_fleet_key_unproven),
        )
        Text(
            stringResource(R.string.phone_radio_fleet_bound, fleet.fleetId, fleet.channel, fleet.keyFingerprint),
            style = MaterialTheme.typography.bodyMedium,
            color = AdosTheme.colors.fgSecondary,
        )
        if (fleet.drones.isEmpty()) {
            Text(stringResource(R.string.phone_radio_fleet_no_drones), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        }
        for (d in fleet.drones) {
            val secret = when (d.secret) {
                RelaySecretState.Held -> stringResource(R.string.phone_radio_fleet_secret_held)
                RelaySecretState.Pending -> stringResource(R.string.phone_radio_fleet_secret_pending)
                RelaySecretState.Conflict -> null
            }
            Text(
                stringResource(R.string.phone_radio_fleet_drone, d.slot, d.deviceId) + (secret?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = AdosTheme.colors.fgPrimary,
            )
            if (d.secret == RelaySecretState.Conflict) {
                StatusChip(StatusLevel.Error, stringResource(R.string.phone_radio_fleet_secret_conflict))
            }
        }
        if (confirming) {
            Text(stringResource(R.string.phone_radio_fleet_forget_confirm), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                AdosButton(stringResource(R.string.phone_radio_keys_forget_yes), onClick = {
                    confirming = false
                    onForget()
                }, variant = AdosButtonVariant.Danger)
                AdosButton(stringResource(R.string.phone_radio_keys_cancel), onClick = { confirming = false }, variant = AdosButtonVariant.Secondary)
            }
        } else {
            AdosButton(stringResource(R.string.phone_radio_keys_forget), onClick = { confirming = true }, variant = AdosButtonVariant.Secondary)
        }
    }
}

/** The "Bind a drone" action that opens the Bind section. */
@Composable
internal fun BindAction(onBind: () -> Unit) {
    BindBody { AdosButton(stringResource(R.string.phone_radio_bind_action), onClick = onBind, icon = Icons.Outlined.Link) }
}

/** Bind progress by stage, the peer proof, and the result with its reason. */
@Composable
internal fun BindSection(bind: BindState, onStart: () -> Unit, onCancel: () -> Unit) {
    BindBody {
        when (bind) {
            BindState.Idle -> {
                Text(stringResource(R.string.phone_radio_bind_hint), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
                AdosButton(stringResource(R.string.phone_radio_bind_start), onClick = onStart)
            }
            is BindState.Running -> {
                StatusChip(StatusLevel.Info, stageText(bind.stage))
                StatusChip(
                    if (bind.peerHeard) StatusLevel.Success else StatusLevel.Warning,
                    stringResource(if (bind.peerHeard) R.string.phone_radio_bind_peer_heard else R.string.phone_radio_bind_peer_silent),
                )
                AdosButton(stringResource(R.string.phone_radio_bind_cancel), onClick = onCancel, variant = AdosButtonVariant.Secondary)
            }
            is BindState.Paired -> StatusChip(StatusLevel.Success, stringResource(R.string.phone_radio_bind_paired, bind.droneVersion, bind.keyFingerprint))
            is BindState.Failed -> {
                StatusChip(StatusLevel.Error, stringResource(R.string.phone_radio_bind_failed, bind.reason))
                AdosButton(stringResource(R.string.phone_radio_bind_start), onClick = onStart)
            }
        }
    }
}

@Composable
private fun stageText(stage: BindStage): String = stringResource(
    when (stage) {
        BindStage.PreparingKeys -> R.string.phone_radio_bind_stage_keys
        BindStage.OpeningRadio -> R.string.phone_radio_bind_stage_radio
        BindStage.WaitingForDrone -> R.string.phone_radio_bind_stage_waiting
        BindStage.Exchanging -> R.string.phone_radio_bind_stage_exchange
        BindStage.Storing -> R.string.phone_radio_bind_stage_storing
    },
)

@Composable
private fun BindBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) { content() }
}
