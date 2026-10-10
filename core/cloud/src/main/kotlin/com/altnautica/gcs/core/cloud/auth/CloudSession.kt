package com.altnautica.gcs.core.cloud.auth

import android.content.Context
import com.altnautica.gcs.core.cloud.CloudBuildConfig
import com.altnautica.gcs.core.cloud.CloudScope
import com.altnautica.gcs.core.cloud.CloudSettings
import com.altnautica.gcs.core.cloud.configured
import com.altnautica.gcs.core.cloud.convex.ConvexClients
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.convex.android.AuthState
import dev.convex.android.ConvexClientWithAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CloudSessionState {
    data object SignedOut : CloudSessionState
    data object Authenticating : CloudSessionState
    data object SignedIn : CloudSessionState
    data class Error(val failure: CloudAuthFailure) : CloudSessionState
}

/** The session state the rest of the cloud layer gates on. */
interface CloudAuthState {
    /** True only when the build has a backend and the operator turned the cloud on. */
    val active: StateFlow<Boolean>
    val state: StateFlow<CloudSessionState>
}

/**
 * The operator's cloud identity. Restores a stored session when the cloud is
 * enabled, signs in and up with email and password, and signs out on the
 * server before wiping the stored tokens.
 *
 * When the credential vault cannot be read or written the state is
 * `Error(VaultUnavailable)`, never a silent sign-out; [retry] reads the vault
 * again and repeats what failed (restoring the session, or the local wipe).
 */
@Singleton
class CloudSession @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val config: CloudBuildConfig,
    private val settings: CloudSettings,
    private val clients: ConvexClients,
    private val vault: CredentialVault,
    @param:CloudScope private val scope: CoroutineScope,
) : CloudAuthState {

    private val _active = MutableStateFlow(false)
    override val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _state = MutableStateFlow<CloudSessionState>(CloudSessionState.SignedOut)
    override val state: StateFlow<CloudSessionState> = _state.asStateFlow()

    private var watcher: Job? = null

    /** The server sign-out ran but the stored refresh token could not be wiped. */
    @Volatile
    private var wipePending = false

    init {
        if (config.configured) {
            scope.launch {
                settings.enabled.distinctUntilChanged().collect { on ->
                    _active.value = on
                    if (on) activate()
                }
            }
        }
    }

    private suspend fun activate() {
        val client = clients.authed ?: return
        if (watcher == null) {
            watcher = scope.launch {
                client.authState.collect { auth ->
                    when (auth) {
                        is AuthState.Authenticated -> _state.value = CloudSessionState.SignedIn
                        is AuthState.Unauthenticated ->
                            if (_state.value == CloudSessionState.SignedIn) {
                                // A background refresh whose rotated token the vault refused is not a sign-out.
                                _state.value = if (clients.provider.holdsUnsavedToken) VAULT_ERROR else CloudSessionState.SignedOut
                            }
                        else -> Unit
                    }
                }
            }
        }
        if (_state.value == CloudSessionState.SignedIn) return
        val stored = try {
            vault.requireReadable()
            vault.cloudRefreshToken()
        } catch (e: VaultUnreadableException) {
            _state.value = VAULT_ERROR
            return
        }
        if (stored == null && !clients.provider.holdsUnsavedToken) {
            if (_state.value == VAULT_ERROR) _state.value = CloudSessionState.SignedOut
            return
        }
        _state.value = CloudSessionState.Authenticating
        client.loginFromCache()
            .onSuccess { _state.value = CloudSessionState.SignedIn }
            .onFailure {
                val failure = ConvexAuthPasswordProvider.classify(it).failure
                _state.value = if (failure == CloudAuthFailure.VaultUnavailable) VAULT_ERROR else CloudSessionState.SignedOut
            }
    }

    /**
     * Reads the credential vault again and repeats the step it failed: the
     * local wipe after a sign-out, otherwise restoring the stored session.
     */
    suspend fun retry() {
        val client = clients.authed ?: return
        vault.retryRead()
        if (wipePending) wipe(client) else activate()
    }

    suspend fun signIn(email: String, password: String) = interactive(PasswordFlow.SignIn(email.trim(), password))

    suspend fun signUp(fullName: String, email: String, password: String) =
        interactive(PasswordFlow.SignUp(fullName.trim(), email.trim(), password))

    private suspend fun interactive(flow: PasswordFlow) {
        val client = clients.authed ?: return
        wipePending = false
        _state.value = CloudSessionState.Authenticating
        clients.provider.pending = flow
        client.login(context)
            .onSuccess { _state.value = CloudSessionState.SignedIn }
            .onFailure { _state.value = CloudSessionState.Error(ConvexAuthPasswordProvider.classify(it).failure) }
    }

    /** Server sign-out, then local wipe. Grants are released by the caller first. */
    suspend fun signOut() {
        val client = clients.authed ?: return
        runCatching { client.action<kotlinx.serialization.json.JsonElement>("auth:signOut", emptyMap<String, Any?>()) }
        wipe(client)
    }

    /** Wipes the stored tokens; a refused wipe stays visible until [retry] completes it. */
    private suspend fun wipe(client: ConvexClientWithAuth<ConvexAuthSession>) {
        client.logout(context)
            .onSuccess {
                wipePending = false
                _state.value = CloudSessionState.SignedOut
            }
            .onFailure {
                wipePending = true
                _state.value = CloudSessionState.Error(ConvexAuthPasswordProvider.classify(it).failure)
            }
    }

    private companion object {
        val VAULT_ERROR = CloudSessionState.Error(CloudAuthFailure.VaultUnavailable)
    }
}
