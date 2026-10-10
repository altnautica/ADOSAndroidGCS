package com.altnautica.gcs.core.cloud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.cloud.R
import com.altnautica.gcs.core.cloud.auth.CloudAuthFailure
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import com.altnautica.gcs.core.cloud.node.ClaimError
import com.altnautica.gcs.core.cloud.node.CloudClaimResult
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.ErrorState
import com.altnautica.gcs.core.designsystem.component.LoadingState
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach

/** Settings → Cloud: opt-in, account, live-control status, claim by code, cloud nodes. */
@Composable
fun CloudSettingsRoute(modifier: Modifier = Modifier, viewModel: CloudSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CloudSettingsScreen(
        state = state,
        onEnabled = { viewModel.setEnabled(it) },
        onSignIn = { e, p -> viewModel.signIn(e, p) },
        onSignUp = { n, e, p -> viewModel.signUp(n, e, p) },
        onSignOut = { viewModel.signOut() },
        onRetry = { viewModel.retrySession() },
        onClaim = { viewModel.claim(it) },
        onSaveClaimedKey = { viewModel.saveClaimedKey() },
        modifier = modifier,
    )
}

@Composable
fun CloudSettingsScreen(
    state: CloudUiState,
    onEnabled: (Boolean) -> Unit,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    onClaim: (String) -> Unit,
    onSaveClaimedKey: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.configured) {
        EmptyState(
            icon = Icons.Outlined.CloudOff,
            title = stringResource(R.string.cloud_not_configured_title),
            message = stringResource(R.string.cloud_not_configured_message),
            modifier = modifier.fillMaxSize(),
        )
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SwitchSettingRow(
            title = stringResource(R.string.cloud_enable),
            summary = stringResource(R.string.cloud_enable_summary),
            checked = state.enabled,
            onCheckedChange = onEnabled,
        )
        if (!state.enabled) {
            EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.cloud_title),
                message = stringResource(R.string.cloud_disabled_message),
            )
            return@Column
        }
        when (val s = state.session) {
            CloudSessionState.Authenticating -> LoadingState(stringResource(R.string.cloud_signing_in))
            CloudSessionState.SignedIn -> SignedInContent(state, onSignOut, onClaim, onSaveClaimedKey)
            CloudSessionState.SignedOut -> SignInForm(null, onSignIn, onSignUp)
            is CloudSessionState.Error -> if (s.failure == CloudAuthFailure.VaultUnavailable) {
                ErrorState(
                    reason = stringResource(R.string.cloud_error_vault_unavailable),
                    retryLabel = stringResource(R.string.cloud_retry),
                    onRetry = onRetry,
                )
            } else {
                SignInForm(s.failure, onSignIn, onSignUp)
            }
        }
    }
}

@Composable
private fun SignInForm(
    failure: CloudAuthFailure?,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String, String) -> Unit,
) {
    var signUp by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().padding(AdosSpacing.S4),
        verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        if (signUp) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.cloud_full_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.cloud_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.cloud_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        failure?.let {
            StatusChip(
                level = StatusLevel.Error,
                label = stringResource(
                    when (it) {
                        CloudAuthFailure.InvalidCredentials -> R.string.cloud_error_invalid_credentials
                        CloudAuthFailure.Network -> R.string.cloud_error_network
                        CloudAuthFailure.RateLimited -> R.string.cloud_error_rate_limited
                        CloudAuthFailure.VaultUnavailable -> R.string.cloud_error_vault_unavailable
                    },
                ),
            )
        }
        val ready = email.isNotBlank() && password.isNotEmpty() && (!signUp || name.isNotBlank())
        AdosButton(
            label = stringResource(if (signUp) R.string.cloud_sign_up else R.string.cloud_sign_in),
            onClick = { if (signUp) onSignUp(name, email, password) else onSignIn(email, password) },
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { signUp = !signUp }) {
            Text(stringResource(if (signUp) R.string.cloud_switch_to_sign_in else R.string.cloud_switch_to_sign_up))
        }
    }
}

@Composable
private fun SignedInContent(
    state: CloudUiState,
    onSignOut: () -> Unit,
    onClaim: (String) -> Unit,
    onSaveClaimedKey: () -> Unit,
) {
    SectionHeader(stringResource(R.string.cloud_section_control))
    Column(Modifier.padding(horizontal = AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        val (level, label) = when (state.grant) {
            GrantStatus.None -> StatusLevel.Neutral to R.string.cloud_grant_none
            GrantStatus.Provisioning -> StatusLevel.Info to R.string.cloud_grant_provisioning
            GrantStatus.Unconfirmed -> StatusLevel.Info to R.string.cloud_grant_unconfirmed
            GrantStatus.Available -> StatusLevel.Success to R.string.cloud_grant_available
            GrantStatus.Expiring -> StatusLevel.Warning to R.string.cloud_grant_expiring
            GrantStatus.Unavailable -> StatusLevel.Warning to R.string.cloud_grant_unavailable
        }
        StatusChip(level = level, label = stringResource(label))
        state.grantError?.takeIf { state.grant == GrantStatus.Unavailable }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
        }
        if (state.grant != GrantStatus.Available && state.grant != GrantStatus.Unconfirmed) {
            Text(
                stringResource(R.string.cloud_grant_queue_note),
                style = MaterialTheme.typography.bodySmall,
                color = AdosTheme.colors.fgSecondary,
            )
        }
    }

    SectionHeader(stringResource(R.string.cloud_section_claim))
    ClaimForm(state.claim, onClaim, onSaveClaimedKey)

    SectionHeader(stringResource(R.string.cloud_section_nodes))
    if (state.nodes.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.FlightTakeoff,
            title = stringResource(R.string.cloud_nodes_empty_title),
            message = stringResource(R.string.cloud_nodes_empty_message),
        )
    } else {
        state.nodes.forEach { NodeRow(it) }
    }

    AdosButton(
        label = stringResource(R.string.cloud_sign_out),
        onClick = onSignOut,
        variant = AdosButtonVariant.Secondary,
        modifier = Modifier.fillMaxWidth().padding(AdosSpacing.S4),
    )
}

@Composable
private fun ClaimForm(claim: ClaimUi, onClaim: (String) -> Unit, onSaveClaimedKey: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(horizontal = AdosSpacing.S4), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase() },
                label = { Text(stringResource(R.string.cloud_claim_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.weight(1f),
            )
            AdosButton(
                label = stringResource(if (claim == ClaimUi.Working) R.string.cloud_claiming else R.string.cloud_claim),
                onClick = { onClaim(code) },
                enabled = code.isNotBlank() && claim != ClaimUi.Working,
            )
        }
        if (claim is ClaimUi.Done) {
            val r = claim.result
            val (level, text) = when (r) {
                is CloudClaimResult.Claimed -> if (r.lanKeySaved) {
                    StatusLevel.Success to stringResource(R.string.cloud_claim_done, r.name ?: r.deviceId.value)
                } else {
                    StatusLevel.Warning to stringResource(R.string.cloud_claim_key_not_saved, r.name ?: r.deviceId.value)
                }
                is CloudClaimResult.Refused -> StatusLevel.Error to stringResource(
                    when (r.error) {
                        ClaimError.InvalidCode, ClaimError.Unknown -> R.string.cloud_claim_invalid
                        ClaimError.Expired -> R.string.cloud_claim_expired
                        ClaimError.AlreadyClaimed -> R.string.cloud_claim_already
                        ClaimError.OwnedByOther -> R.string.cloud_claim_owned
                        ClaimError.RateLimited -> R.string.cloud_claim_rate_limited
                        ClaimError.VaultUnavailable -> R.string.cloud_claim_vault_unavailable
                    },
                )
                is CloudClaimResult.Failed -> StatusLevel.Error to stringResource(R.string.cloud_claim_failed, r.message)
            }
            StatusChip(level = level, label = text)
            if (r is CloudClaimResult.Claimed && !r.lanKeySaved) {
                AdosButton(
                    label = stringResource(R.string.cloud_claim_save_again),
                    onClick = onSaveClaimedKey,
                    variant = AdosButtonVariant.Secondary,
                )
            }
        }
    }
}

@Composable
private fun NodeRow(node: NodeSummary) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
    ) {
        Text(node.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val (level, label) = when (node.liveness) {
            Liveness.Live -> StatusLevel.Success to R.string.cloud_liveness_live
            Liveness.Stale -> StatusLevel.Warning to R.string.cloud_liveness_stale
            Liveness.Offline -> StatusLevel.Neutral to R.string.cloud_liveness_offline
        }
        StatusChip(level = level, label = stringResource(label))
        StatusChip(
            level = StatusLevel.Info,
            label = stringResource(if (Reach.CloudLive in node.reaches) R.string.cloud_reach_live else R.string.cloud_reach_queue),
        )
    }
}

@Preview
@Composable
private fun CloudSignedInPreview() = AdosTheme {
    CloudSettingsScreen(
        state = CloudUiState(
            configured = true,
            enabled = true,
            session = CloudSessionState.SignedIn,
            grant = GrantStatus.Unconfirmed,
            nodes = listOf(
                NodeSummary(DeviceId("a1"), "Survey quad", NodeProfile.Drone, listOf(Reach.CloudLive, Reach.CloudQueue), Liveness.Live, "1.0.0", FcVariant.MavlinkArdupilot, null, null),
            ),
        ),
        onEnabled = {}, onSignIn = { _, _ -> }, onSignUp = { _, _, _ -> }, onSignOut = {}, onRetry = {}, onClaim = {}, onSaveClaimedKey = {},
    )
}

@Preview
@Composable
private fun CloudSignInPreview() = AdosTheme {
    CloudSettingsScreen(
        state = CloudUiState(configured = true, enabled = true, session = CloudSessionState.Error(CloudAuthFailure.InvalidCredentials)),
        onEnabled = {}, onSignIn = { _, _ -> }, onSignUp = { _, _, _ -> }, onSignOut = {}, onRetry = {}, onClaim = {}, onSaveClaimedKey = {},
    )
}

@Preview
@Composable
private fun CloudVaultUnavailablePreview() = AdosTheme {
    CloudSettingsScreen(
        state = CloudUiState(configured = true, enabled = true, session = CloudSessionState.Error(CloudAuthFailure.VaultUnavailable)),
        onEnabled = {}, onSignIn = { _, _ -> }, onSignUp = { _, _, _ -> }, onSignOut = {}, onRetry = {}, onClaim = {}, onSaveClaimedKey = {},
    )
}
