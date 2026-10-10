package com.altnautica.gcs.radio.rx.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.annotation.StringRes
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.radio.rx.R
import com.altnautica.gcs.radio.rx.invite.EnrollmentState
import com.altnautica.gcs.radio.rx.invite.InviteFailure
import com.altnautica.gcs.radio.rx.invite.StoredInvite
import com.altnautica.gcs.radio.rx.node.PhoneRadioNodeSource
import com.altnautica.gcs.radio.rx.session.DroneLinkStatus
import com.altnautica.gcs.radio.rx.session.PhoneRadioFailure
import com.altnautica.gcs.radio.rx.session.PhoneRadioState
import com.altnautica.gcs.radio.rx.session.PhoneRadioStats
import com.altnautica.gcs.core.model.Liveness
import kotlinx.coroutines.delay

/** Settings → Phone radio (also reachable from Connect). */
@Composable
fun PhoneRadioRoute(modifier: Modifier = Modifier, viewModel: PhoneRadioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PhoneRadioScreen(
        state = state,
        onAllowUsb = viewModel::requestPermission,
        onEnroll = viewModel::enroll,
        onCancelEnroll = viewModel::cancelEnrollment,
        onForget = viewModel::forget,
        onRetryKeys = viewModel::retryKeys,
        onSaveAgain = viewModel::saveAgain,
        onStart = viewModel::start,
        onStop = viewModel::stop,
        modifier = modifier,
    )
}

@Composable
fun PhoneRadioScreen(
    state: PhoneRadioUiState,
    onAllowUsb: () -> Unit,
    onEnroll: (GroundOption) -> Unit,
    onCancelEnroll: () -> Unit,
    onForget: () -> Unit,
    onRetryKeys: () -> Unit,
    onSaveAgain: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        LoadingState(stringResource(R.string.phone_radio_title), modifier.fillMaxSize())
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.phone_radio_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = AdosTheme.colors.fgSecondary,
            modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        )
        SectionHeader(stringResource(R.string.phone_radio_section_dongle))
        DongleSection(state.dongle, onAllowUsb)
        SectionHeader(stringResource(R.string.phone_radio_section_keys))
        KeysSection(state, onEnroll, onCancelEnroll, onForget, onRetryKeys, onSaveAgain)
        SectionHeader(stringResource(R.string.phone_radio_section_receiver))
        ReceiverSection(state, onStart, onStop)
    }
}

@Composable
private fun DongleSection(dongle: DongleUi, onAllowUsb: () -> Unit) {
    when (dongle) {
        DongleUi.None -> EmptyState(
            icon = Icons.Outlined.Usb,
            title = stringResource(R.string.phone_radio_dongle_none_title),
            message = stringResource(R.string.phone_radio_dongle_none_message),
        )
        is DongleUi.Unsupported -> Body {
            StatusChip(StatusLevel.Error, stringResource(R.string.phone_radio_dongle_unsupported, dongle.productId))
        }
        is DongleUi.NeedsPermission -> Body {
            StatusChip(StatusLevel.Warning, stringResource(R.string.phone_radio_dongle_permission, dongle.chip))
            AdosButton(stringResource(R.string.phone_radio_dongle_allow), onClick = onAllowUsb, icon = Icons.Outlined.Usb)
        }
        is DongleUi.Ready -> Body {
            StatusChip(StatusLevel.Success, stringResource(R.string.phone_radio_dongle_ready, dongle.chip))
        }
    }
}

@Composable
private fun KeysSection(
    state: PhoneRadioUiState,
    onEnroll: (GroundOption) -> Unit,
    onCancel: () -> Unit,
    onForget: () -> Unit,
    onRetryKeys: () -> Unit,
    onSaveAgain: () -> Unit,
) {
    val invite = state.invite
    val enrollment = state.enrollment
    if (state.keysError == KeysError.Unreadable) {
        // Whether keys are stored is unknown: offer neither "no keys" nor a new request.
        VaultError(R.string.phone_radio_keys_unreadable, R.string.phone_radio_keys_retry, onRetryKeys)
        return
    }
    if (invite != null && enrollment !is EnrollmentState.AwaitingApproval && enrollment !is EnrollmentState.NotSaved) {
        StoredKeys(invite, onForget)
        if (state.keysError == KeysError.NotForgotten) {
            VaultError(R.string.phone_radio_keys_not_forgotten, R.string.phone_radio_keys_forget_again, onRetryKeys)
        }
        return
    }
    when (enrollment) {
        EnrollmentState.Requesting -> LoadingState(stringResource(R.string.phone_radio_keys_requesting))
        is EnrollmentState.AwaitingApproval -> Awaiting(enrollment, onCancel)
        is EnrollmentState.Approved -> StoredKeys(enrollment.invite, onForget)
        // The ground station hands the keys out once: keep them on screen until they are stored.
        is EnrollmentState.NotSaved -> VaultError(R.string.phone_radio_keys_not_saved, R.string.phone_radio_keys_save_again, onSaveAgain)
        else -> {
            enrollment?.let { Body { StatusChip(StatusLevel.Error, enrollmentMessage(it)) } }
            GroundPicker(state.grounds, onEnroll)
        }
    }
}

@Composable
private fun VaultError(@StringRes message: Int, @StringRes action: Int, onRetry: () -> Unit) {
    Body {
        StatusChip(StatusLevel.Error, stringResource(message), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        AdosButton(stringResource(action), onClick = onRetry)
    }
}

@Composable
private fun enrollmentMessage(state: EnrollmentState): String = when (state) {
    EnrollmentState.Rejected -> stringResource(R.string.phone_radio_keys_rejected)
    EnrollmentState.Expired -> stringResource(R.string.phone_radio_keys_expired)
    EnrollmentState.Unsupported -> stringResource(R.string.phone_radio_keys_unsupported)
    is EnrollmentState.InvalidInvite -> stringResource(R.string.phone_radio_keys_invalid, state.detail)
    is EnrollmentState.Failed -> failureMessage(state.reason)
    else -> ""
}

@Composable
private fun failureMessage(reason: InviteFailure): String = stringResource(
    when (reason) {
        InviteFailure.NotPaired -> R.string.phone_radio_failure_not_paired
        InviteFailure.Unauthorized -> R.string.phone_radio_failure_unauthorized
        InviteFailure.GroundNotLinked -> R.string.phone_radio_failure_ground_not_linked
        InviteFailure.TooManyPending -> R.string.phone_radio_failure_too_many
        InviteFailure.InvalidKey -> R.string.phone_radio_failure_invalid_key
        InviteFailure.Network -> R.string.phone_radio_failure_network
        InviteFailure.Server -> R.string.phone_radio_failure_server
    },
)

@Composable
private fun GroundPicker(grounds: List<GroundOption>, onEnroll: (GroundOption) -> Unit) {
    if (grounds.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Hub,
            title = stringResource(R.string.phone_radio_keys_no_ground_title),
            message = stringResource(R.string.phone_radio_keys_no_ground_message),
        )
        return
    }
    Body {
        Text(stringResource(R.string.phone_radio_keys_none), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        for (g in grounds) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, color = AdosTheme.colors.fgPrimary)
                    if (!g.live) {
                        Text(stringResource(R.string.phone_radio_keys_offline), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
                    }
                }
                AdosButton(
                    stringResource(R.string.phone_radio_keys_request),
                    onClick = { onEnroll(g) },
                    variant = AdosButtonVariant.Secondary,
                    enabled = g.live,
                )
            }
        }
    }
}

@Composable
private fun Awaiting(state: EnrollmentState.AwaitingApproval, onCancel: () -> Unit) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.expiresAtMs) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val secondsLeft = ((state.expiresAtMs - nowMs) / 1000).coerceAtLeast(0).toInt()
    Body {
        Text(stringResource(R.string.phone_radio_keys_awaiting_title), style = MaterialTheme.typography.titleMedium, color = AdosTheme.colors.fgPrimary)
        Text(
            state.fingerprint,
            style = AdosTypography.numeric.display,
            color = AdosTheme.colors.accentPrimary,
        )
        Text(stringResource(R.string.phone_radio_keys_awaiting_message), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        Text(stringResource(R.string.phone_radio_keys_expires, secondsLeft), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
        AdosButton(stringResource(R.string.phone_radio_keys_cancel), onClick = onCancel, variant = AdosButtonVariant.Secondary)
    }
}

@Composable
private fun StoredKeys(invite: StoredInvite, onForget: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Body {
        StatusChip(StatusLevel.Success, stringResource(R.string.phone_radio_keys_stored, invite.groundName ?: invite.groundId))
        Text(
            stringResource(
                R.string.phone_radio_keys_detail,
                invite.bundle.fleetId,
                invite.bundle.slots.size,
                invite.bundle.channel,
                invite.phoneFingerprint,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = AdosTheme.colors.fgSecondary,
        )
        if (confirming) {
            Text(stringResource(R.string.phone_radio_keys_forget_confirm), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgPrimary)
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

@Composable
private fun ReceiverSection(state: PhoneRadioUiState, onStart: () -> Unit, onStop: () -> Unit) {
    val radio = state.radio
    Body {
        val (level, label) = when (radio) {
            PhoneRadioState.Stopped -> StatusLevel.Neutral to stringResource(R.string.phone_radio_state_stopped)
            PhoneRadioState.Starting -> StatusLevel.Info to stringResource(R.string.phone_radio_state_starting)
            is PhoneRadioState.Receiving -> StatusLevel.Success to stringResource(R.string.phone_radio_state_receiving, radio.channel)
            is PhoneRadioState.Failed -> StatusLevel.Error to stringResource(R.string.phone_radio_state_failed, radioFailure(radio))
        }
        StatusChip(level, label, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, icon = Icons.Outlined.SettingsInputAntenna)
        val running = radio is PhoneRadioState.Receiving || radio is PhoneRadioState.Starting
        if (running) {
            AdosButton(stringResource(R.string.phone_radio_stop), onClick = onStop, variant = AdosButtonVariant.Secondary)
        } else {
            AdosButton(stringResource(R.string.phone_radio_start), onClick = onStart, enabled = state.canStart)
            if (!state.canStart) {
                Text(stringResource(R.string.phone_radio_needs), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
            }
        }
        state.stats?.let { Stats(it) }
        if (state.links.isNotEmpty()) Drones(state.links)
    }
}

@Composable
private fun radioFailure(state: PhoneRadioState.Failed): String {
    val base = stringResource(
        when (state.reason) {
            PhoneRadioFailure.NoInvite -> R.string.phone_radio_failed_no_invite
            PhoneRadioFailure.KeysUnreadable -> R.string.phone_radio_failed_keys_unreadable
            PhoneRadioFailure.NoDongle -> R.string.phone_radio_failed_no_dongle
            PhoneRadioFailure.NoPermission -> R.string.phone_radio_failed_no_permission
            PhoneRadioFailure.OpenFailed -> R.string.phone_radio_failed_open
            PhoneRadioFailure.ServiceLost -> R.string.phone_radio_failed_service
            PhoneRadioFailure.RadioStopped -> R.string.phone_radio_failed_radio
        },
    )
    return state.detail?.let { "$base ($it)" } ?: base
}

@Composable
private fun Stats(stats: PhoneRadioStats) {
    val none = stringResource(R.string.phone_radio_no_value)
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        StatRow(R.string.phone_radio_stat_channel, stringResource(R.string.phone_radio_stat_channel_value, stats.channel, stats.bandwidthMhz))
        StatRow(R.string.phone_radio_stat_rssi, stats.rssiDbm?.let { stringResource(R.string.phone_radio_stat_rssi_value, it) } ?: none)
        StatRow(R.string.phone_radio_stat_snr, stats.snrDb?.let { stringResource(R.string.phone_radio_stat_snr_value, it) } ?: none)
        StatRow(R.string.phone_radio_stat_video, perSecond(stats.videoPacketsPerSec))
        StatRow(R.string.phone_radio_stat_telemetry, perSecond(stats.telemetryPacketsPerSec))
        StatRow(R.string.phone_radio_stat_fec, perSecond(stats.fecRecoveredPerSec))
        StatRow(R.string.phone_radio_stat_lost, perSecond(stats.lostPerSec))
        StatRow(R.string.phone_radio_stat_decrypt, perSecond(stats.decryptErrorsPerSec))
        StatRow(R.string.phone_radio_stat_usb, perSecond(stats.usbFramesPerSec))
        StatRow(R.string.phone_radio_stat_dropped, perSecond(stats.usbDroppedPerSec))
        StatRow(R.string.phone_radio_stat_hops, stats.hopsFollowed.toString())
    }
}

@Composable
private fun perSecond(value: Long): String = stringResource(R.string.phone_radio_per_second, value.toInt())

@Composable
private fun StatRow(@StringRes label: Int, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary, modifier = Modifier.weight(1f))
        Text(value, style = AdosTypography.numeric.medium, color = AdosTheme.colors.fgPrimary)
    }
}

@Composable
private fun Drones(links: List<DroneLinkStatus>) {
    var nowMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    Text(stringResource(R.string.phone_radio_drones), style = MaterialTheme.typography.titleSmall, color = AdosTheme.colors.fgPrimary)
    for (link in links) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(link.deviceId.value, style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgPrimary)
                Text(stringResource(R.string.phone_radio_drone_slot, link.slot), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
            }
            when (PhoneRadioNodeSource.liveness(link.lastPacketMs, nowMs)) {
                Liveness.Live -> StatusChip(StatusLevel.Success, stringResource(R.string.phone_radio_drone_live))
                Liveness.Stale -> StatusChip(StatusLevel.Warning, stringResource(R.string.phone_radio_drone_stale))
                Liveness.Offline -> StatusChip(StatusLevel.Neutral, stringResource(R.string.phone_radio_drone_silent))
            }
        }
    }
}

@Composable
private fun Body(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) { content() }
}

@Preview
@Composable
private fun PhoneRadioScreenPreview() = AdosTheme {
    PhoneRadioScreen(
        state = PhoneRadioUiState(
            loading = false,
            dongle = DongleUi.Ready("RTL8812EU"),
            grounds = listOf(GroundOption(DeviceId("gs-1"), "Field ground station", live = true)),
            radio = PhoneRadioState.Stopped,
        ),
        onAllowUsb = {},
        onEnroll = {},
        onCancelEnroll = {},
        onForget = {},
        onRetryKeys = {},
        onSaveAgain = {},
        onStart = {},
        onStop = {},
    )
}
