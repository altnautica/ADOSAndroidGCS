package com.altnautica.gcs.core.cloud.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.cloud.CloudBuildConfig
import com.altnautica.gcs.core.cloud.CloudSettings
import com.altnautica.gcs.core.cloud.WallClock
import com.altnautica.gcs.core.cloud.auth.CloudSession
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import com.altnautica.gcs.core.cloud.configured
import com.altnautica.gcs.core.cloud.grant.GrantManager
import com.altnautica.gcs.core.cloud.grant.GrantState
import com.altnautica.gcs.core.cloud.grant.MqttControlAuthority
import com.altnautica.gcs.core.cloud.node.CloudClaimResult
import com.altnautica.gcs.core.cloud.node.CloudNodeSource
import com.altnautica.gcs.core.cloud.node.CloudPairing
import com.altnautica.gcs.core.model.NodeSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The held grant, as the operator sees it. */
enum class GrantStatus { None, Provisioning, Unconfirmed, Available, Expiring, Unavailable }

data class CloudUiState(
    val configured: Boolean,
    val enabled: Boolean = false,
    val session: CloudSessionState = CloudSessionState.SignedOut,
    val grant: GrantStatus = GrantStatus.None,
    val grantError: String? = null,
    val nodes: List<NodeSummary> = emptyList(),
    val claim: ClaimUi = ClaimUi.Idle,
)

sealed interface ClaimUi {
    data object Idle : ClaimUi
    data object Working : ClaimUi
    data class Done(val result: CloudClaimResult) : ClaimUi
}

@HiltViewModel
class CloudSettingsViewModel @Inject constructor(
    config: CloudBuildConfig,
    private val settings: CloudSettings,
    private val session: CloudSession,
    private val grants: GrantManager,
    private val pairing: CloudPairing,
    nodeSource: CloudNodeSource,
    private val clock: WallClock,
) : ViewModel() {

    private val claim = MutableStateFlow<ClaimUi>(ClaimUi.Idle)
    private val configured = config.configured

    val state: StateFlow<CloudUiState> = combine(
        settings.enabled,
        session.state,
        grants.state,
        nodeSource.nodes,
        claim,
    ) { enabled, s, g, nodes, c ->
        CloudUiState(
            configured = configured,
            enabled = enabled,
            session = s,
            grant = grantStatus(g, clock.nowMs()),
            grantError = g.error,
            nodes = nodes,
            claim = c,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CloudUiState(configured = configured))

    fun setEnabled(on: Boolean) = viewModelScope.launch { settings.setEnabled(on) }

    fun signIn(email: String, password: String) = viewModelScope.launch { session.signIn(email, password) }

    fun signUp(name: String, email: String, password: String) = viewModelScope.launch { session.signUp(name, email, password) }

    fun signOut() = viewModelScope.launch {
        grants.release()
        session.signOut()
    }

    /** Reads the credential vault again and repeats the session step it refused. */
    fun retrySession() = viewModelScope.launch { session.retry() }

    fun claim(code: String) = viewModelScope.launch {
        claim.value = ClaimUi.Working
        claim.value = ClaimUi.Done(pairing.claim(code))
    }

    /** Stores a claimed node's key the vault refused; the result line updates once it is stored. */
    fun saveClaimedKey() = viewModelScope.launch {
        val shown = claim.value as? ClaimUi.Done ?: return@launch
        val claimed = shown.result as? CloudClaimResult.Claimed ?: return@launch
        if (pairing.retrySavePending()) {
            claim.compareAndSet(shown, ClaimUi.Done(claimed.copy(lanKeySaved = true)))
        }
    }

    companion object {
        fun grantStatus(g: GrantState, nowMs: Long): GrantStatus {
            val held = g.grant
            return when {
                held == null && g.minting -> GrantStatus.Provisioning
                held == null -> if (g.error != null) GrantStatus.Unavailable else GrantStatus.None
                held.expiresAtMs <= nowMs -> if (g.minting) GrantStatus.Provisioning else GrantStatus.Unavailable
                g.renewalFailed && held.expiresAtMs - nowMs <= MqttControlAuthority.EXPIRY_WARNING_MS -> GrantStatus.Expiring
                !held.writeConfirmed -> GrantStatus.Unconfirmed
                else -> GrantStatus.Available
            }
        }
    }
}
