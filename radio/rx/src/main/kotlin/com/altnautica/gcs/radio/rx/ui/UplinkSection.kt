package com.altnautica.gcs.radio.rx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.radio.rx.R
import com.altnautica.gcs.radio.rx.tx.UplinkState
import com.altnautica.gcs.radio.rx.uplink.BoxStatus
import com.altnautica.gcs.radio.rx.uplink.HandoverNote
import com.altnautica.gcs.radio.rx.uplink.HandoverPhase
import com.altnautica.gcs.radio.rx.uplink.HoldSource

/** The box the stored invite came from, as the Handover section shows it. */
data class HandoverBoxUi(
    val deviceId: String,
    val name: String,
    /** Null until the first read of the box's uplink answered or failed. */
    val status: BoxStatus?,
)

/** The uplink and handover actions the screen offers. */
data class UplinkActions(val onTake: () -> Unit = {}, val onGiveBack: () -> Unit = {})

/**
 * Uplink: the phone's handover state, the uplink liveness while it
 * transmits (only `active` reads as transmitting with the drone answering),
 * and Take uplink / Give back.
 */
@Composable
internal fun UplinkSection(state: PhoneRadioUiState, actions: UplinkActions) {
    val handover = state.handover
    SectionBody {
        val (level, label) = when (handover.phase) {
            HandoverPhase.ReceiveOnly -> StatusLevel.Neutral to stringResource(R.string.phone_radio_phase_receive_only)
            HandoverPhase.Taking -> StatusLevel.Info to stringResource(R.string.phone_radio_phase_taking)
            HandoverPhase.Transmitting -> StatusLevel.Success to stringResource(R.string.phone_radio_phase_transmitting)
            HandoverPhase.Yielded -> StatusLevel.Warning to stringResource(R.string.phone_radio_phase_yielded)
        }
        StatusChip(level, label, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (handover.phase == HandoverPhase.Transmitting) {
            when (handover.source) {
                HoldSource.Taken -> stringResource(R.string.phone_radio_source_taken, state.handoverBox?.name.orEmpty())
                HoldSource.OwnFleet -> stringResource(R.string.phone_radio_source_own)
                HoldSource.Bench -> stringResource(R.string.phone_radio_source_bench)
                null -> null
            }?.let { Caption(it) }
            state.uplink?.let { UplinkLiveness(it) }
        }
        handover.note?.let { note ->
            Text(
                noteText(note),
                style = MaterialTheme.typography.bodyMedium,
                color = AdosTheme.colors.fgPrimary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        val takenHere = handover.phase == HandoverPhase.Transmitting && handover.source == HoldSource.Taken
        when {
            takenHere && handover.busy -> Caption(stringResource(R.string.phone_radio_giving_back))
            takenHere -> AdosButton(stringResource(R.string.phone_radio_give_back), onClick = actions.onGiveBack, variant = AdosButtonVariant.Secondary)
            state.invite != null && (handover.phase == HandoverPhase.ReceiveOnly || handover.phase == HandoverPhase.Yielded) ->
                AdosButton(stringResource(R.string.phone_radio_take), onClick = actions.onTake, enabled = !handover.busy)
        }
    }
}

/** Handover: the box this fleet belongs to, its uplink state and whether the phone reaches it over the LAN. */
@Composable
internal fun HandoverSection(state: PhoneRadioUiState) {
    SectionBody {
        val box = state.handoverBox
        if (state.invite == null) {
            Caption(stringResource(R.string.phone_radio_handover_bound_here))
            return@SectionBody
        }
        if (!state.handoverChecked) {
            Caption(stringResource(R.string.phone_radio_handover_checking))
            return@SectionBody
        }
        if (box == null) {
            Caption(stringResource(R.string.phone_radio_handover_no_role))
            return@SectionBody
        }
        Text(stringResource(R.string.phone_radio_handover_box), style = MaterialTheme.typography.titleSmall, color = AdosTheme.colors.fgPrimary)
        Text(box.name, style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgPrimary)
        Caption(box.deviceId)
        when (val s = box.status) {
            null -> Caption(stringResource(R.string.phone_radio_handover_checking))
            BoxStatus.Unreachable -> StatusChip(StatusLevel.Warning, stringResource(R.string.phone_radio_handover_unreachable))
            is BoxStatus.Reached -> {
                StatusChip(StatusLevel.Success, stringResource(R.string.phone_radio_handover_reachable))
                val uplink = s.uplink
                when {
                    uplink == null -> Caption(stringResource(R.string.phone_radio_handover_box_unreported))
                    uplink.state == "standby" -> StatusChip(
                        StatusLevel.Info,
                        stringResource(R.string.phone_radio_handover_box_standby, uplink.holderName ?: uplink.holderDeviceId.orEmpty()),
                    )
                    else -> StatusChip(StatusLevel.Neutral, stringResource(R.string.phone_radio_handover_box_transmitting))
                }
            }
        }
    }
}

/** The uplink while the phone holds it. Only Active may read as transmitting. */
@Composable
private fun UplinkLiveness(uplink: UplinkState) {
    val (level, label) = when (uplink) {
        UplinkState.Active -> StatusLevel.Success to R.string.phone_radio_uplink_active
        UplinkState.Unverified -> StatusLevel.Info to R.string.phone_radio_uplink_unverified
        UplinkState.Down -> StatusLevel.Error to R.string.phone_radio_uplink_down
    }
    StatusChip(level, stringResource(label), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun noteText(note: HandoverNote): String = when (note) {
    HandoverNote.NoRadio -> stringResource(R.string.phone_radio_note_no_radio)
    HandoverNote.NoHandoverKey -> stringResource(R.string.phone_radio_note_no_key)
    is HandoverNote.NoLanReach -> stringResource(R.string.phone_radio_note_no_lan, note.box)
    is HandoverNote.ListenerFailed -> stringResource(R.string.phone_radio_note_listener, note.port)
    is HandoverNote.OtherGround -> stringResource(R.string.phone_radio_note_other_ground, note.deviceId)
    // The box's own sentence.
    is HandoverNote.BoxRefused -> note.detail
    is HandoverNote.TxDidNotOpen -> stringResource(R.string.phone_radio_note_tx_did_not_open, note.box)
    is HandoverNote.GaveBack -> stringResource(R.string.phone_radio_note_gave_back, note.box)
    is HandoverNote.GiveBackUnconfirmed -> note.detail?.let { stringResource(R.string.phone_radio_note_give_back_refused, note.box, it) }
        ?: stringResource(R.string.phone_radio_note_give_back_unconfirmed, note.box)
    is HandoverNote.TxNotQuiet -> stringResource(R.string.phone_radio_note_tx_not_quiet, note.box)
    is HandoverNote.TakenBack -> stringResource(R.string.phone_radio_note_taken_back, note.box)
    HandoverNote.RadioStopped -> stringResource(R.string.phone_radio_note_radio_stopped)
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
}

@Composable
private fun SectionBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) { content() }
}
