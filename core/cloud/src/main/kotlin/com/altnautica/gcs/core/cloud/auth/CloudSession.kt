package com.altnautica.gcs.core.cloud.auth

import android.content.Context
import com.altnautica.gcs.core.cloud.CloudBuildConfig
import com.altnautica.gcs.core.cloud.CloudScope
import com.altnautica.gcs.core.cloud.CloudSettings
import com.altnautica.gcs.core.cloud.configured
import com.altnautica.gcs.core.cloud.convex.ConvexClients
import com.altnautica.gcs.core.data.vault.CredentialVault
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.convex.android.AuthState
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
                            if (_state.value == CloudSessionState.SignedIn) _state.value = CloudSessionState.SignedOut
                        else -> Unit
                    }
                }
            }
        }
        if (_state.value == CloudSessionState.SignedIn || vault.cloudRefreshToken() == null) return
        _state.value = CloudSessionState.Authenticating
        client.loginFromCache()
            .onSuccess { _state.value = CloudSessionState.SignedIn }
            .onFailure { _state.value = CloudSessionState.SignedOut }
    }

    suspend fun signIn(email: String, password: String) = interactive(PasswordFlow.SignIn(email.trim(), password))

    suspend fun signUp(fullName: String, email: String, password: String) =
        interactive(PasswordFlow.SignUp(fullName.trim(), email.trim(), password))

    private suspend fun interactive(flow: PasswordFlow) {
        val client = clients.authed ?: return
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
        client.logout(context)
        _state.value = CloudSessionState.SignedOut
    }
}
