package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.GlassSurface
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.radio.rx.R
import com.altnautica.gcs.radio.rx.invite.InviteCallResult
import com.altnautica.gcs.radio.rx.invite.PendingInvite
import com.altnautica.gcs.radio.rx.invite.PhoneInviteApi
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the phone-receivers card shows for one ground station. */
sealed interface PhoneReceiversState {
    data object Loading : PhoneReceiversState

    /** The node does not issue phone invites: the card is not shown. */
    data object Unsupported : PhoneReceiversState
    data object Error : PhoneReceiversState
    data class Pending(val invites: List<PendingInvite>, val nowMs: Long, val actionFailed: Boolean) : PhoneReceiversState
}

/**
 * Ground station → Radio tab: phones asking for the fleet's radio keys,
 * each with the code the phone shows, to approve or reject. Polls while
 * visible.
 */
@Composable
fun PhoneReceiversCard(nodeId: String, modifier: Modifier = Modifier) {
    val vm = hiltViewModel<PhoneReceiversViewModel, PhoneReceiversViewModel.Factory>(key = "phone-receivers-$nodeId") { it.create(nodeId) }
    val state by vm.state.collectAsStateWithLifecycle()
    PhoneReceiversContent(state, onApprove = vm::approve, onReject = vm::reject, onRetry = vm::refresh, modifier = modifier)
}

@Composable
fun PhoneReceiversContent(
    state: PhoneReceiversState,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == PhoneReceiversState.Unsupported) return
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.phone_receivers_title))
        when (state) {
            PhoneReceiversState.Loading -> LoadingState(stringResource(R.string.phone_receivers_loading))
            PhoneReceiversState.Error -> ErrorState(
                reason = stringResource(R.string.phone_receivers_error),
                retryLabel = stringResource(R.string.phone_receivers_retry),
                onRetry = onRetry,
            )
            is PhoneReceiversState.Pending -> PendingList(state, onApprove, onReject)
            PhoneReceiversState.Unsupported -> Unit
        }
    }
}

@Composable
private fun PendingList(state: PhoneReceiversState.Pending, onApprove: (String) -> Unit, onReject: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) {
        if (state.invites.isEmpty()) {
            Text(stringResource(R.string.phone_receivers_empty), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
            return@Column
        }
        Text(stringResource(R.string.phone_receivers_hint), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        if (state.actionFailed) StatusChip(StatusLevel.Error, stringResource(R.string.phone_receivers_action_failed))
        for (invite in state.invites) {
            GlassSurface(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip(
                            StatusLevel.Info,
                            invite.label?.takeIf { it.isNotBlank() } ?: stringResource(R.string.phone_receivers_unnamed),
                            icon = Icons.Outlined.PhoneAndroid,
                        )
                    }
                    Text(
                        stringResource(R.string.phone_receivers_code, invite.phoneFingerprint),
                        style = AdosTypography.numeric.large,
                        color = AdosTheme.colors.fgPrimary,
                    )
                    val secondsLeft = ((invite.expiresAtMs - state.nowMs) / 1000).coerceAtLeast(0).toInt()
                    Text(stringResource(R.string.phone_receivers_expires, secondsLeft), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgTertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                        AdosButton(stringResource(R.string.phone_receivers_approve), onClick = { onApprove(invite.inviteId) }, icon = Icons.Outlined.Check)
                        AdosButton(
                            stringResource(R.string.phone_receivers_reject),
                            onClick = { onReject(invite.inviteId) },
                            variant = AdosButtonVariant.Secondary,
                            icon = Icons.Outlined.Close,
                        )
                    }
                }
            }
        }
    }
}

@HiltViewModel(assistedFactory = PhoneReceiversViewModel.Factory::class)
class PhoneReceiversViewModel @AssistedInject constructor(
    @Assisted nodeId: String,
    private val api: PhoneInviteApi,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(nodeId: String): PhoneReceiversViewModel
    }

    private val ground = DeviceId(nodeId)
    private val listing = MutableStateFlow<Listing>(Listing.Loading)
    private val actionFailed = MutableStateFlow(false)

    /** Re-reads the list every [POLL_MS] while the card is visible; each tick also moves the countdowns. */
    private val polling = flow {
        while (true) {
            fetch()
            emit(System.currentTimeMillis())
            delay(POLL_MS)
        }
    }.onStart { emit(System.currentTimeMillis()) }

    val state: StateFlow<PhoneReceiversState> = combine(listing, actionFailed, polling) { list, failed, nowMs ->
        when (list) {
            Listing.Loading -> PhoneReceiversState.Loading
            Listing.Unsupported -> PhoneReceiversState.Unsupported
            Listing.Error -> PhoneReceiversState.Error
            is Listing.Ok -> PhoneReceiversState.Pending(list.invites, nowMs, failed)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), PhoneReceiversState.Loading)

    fun approve(inviteId: String) = act { api.approve(ground, inviteId) }

    fun reject(inviteId: String) = act { api.reject(ground, inviteId) }

    fun refresh() {
        viewModelScope.launch {
            listing.value = Listing.Loading
            fetch()
        }
    }

    private fun act(call: suspend () -> InviteCallResult<Unit>) {
        viewModelScope.launch {
            actionFailed.value = call() !is InviteCallResult.Ok
            fetch()
        }
    }

    private suspend fun fetch(): Listing = when (val r = api.pending(ground)) {
        is InviteCallResult.Ok -> Listing.Ok(r.value.sortedBy { it.expiresAtMs })
        InviteCallResult.Unsupported -> Listing.Unsupported
        is InviteCallResult.Failed -> Listing.Error
    }.also { listing.value = it }

    private sealed interface Listing {
        data object Loading : Listing
        data object Unsupported : Listing
        data object Error : Listing
        data class Ok(val invites: List<PendingInvite>) : Listing
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val STOP_MS = 5_000L
    }
}

@Preview
@Composable
private fun PhoneReceiversPreview() = AdosTheme {
    PhoneReceiversContent(
        state = PhoneReceiversState.Pending(
            invites = listOf(PendingInvite("0123456789abcdef0123456789abcdef", "Pixel 9", "AAA8-FFF7-03B5-0B22", 1_000_120_000L)),
            nowMs = 1_000_000_000L,
            actionFailed = false,
        ),
        onApprove = {},
        onReject = {},
        onRetry = {},
    )
}
