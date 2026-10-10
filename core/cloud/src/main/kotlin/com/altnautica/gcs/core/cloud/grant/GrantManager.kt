package com.altnautica.gcs.core.cloud.grant

import com.altnautica.gcs.core.cloud.CloudScope
import com.altnautica.gcs.core.cloud.WallClock
import com.altnautica.gcs.core.cloud.auth.CloudAuthState
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import com.altnautica.gcs.core.cloud.convex.CloudJson
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/** A minted broker-write grant. [secret] lives in memory only and is never logged. */
data class HeldGrant(
    val principal: String,
    val secret: String,
    val deviceIds: List<String>,
    val expiresAtMs: Long,
    val writeConfirmed: Boolean,
) {
    override fun toString(): String = "HeldGrant(principal=$principal, devices=$deviceIds, expiresAtMs=$expiresAtMs, confirmed=$writeConfirmed)"
}

data class GrantState(
    val grant: HeldGrant? = null,
    val minting: Boolean = false,
    val renewalFailed: Boolean = false,
    val error: String? = null,
)

/** What a transport needs from the grant holder. */
interface GrantSource {
    val state: StateFlow<GrantState>

    /** Records that the broker accepted a write under [principal]. */
    suspend fun confirmWrite(principal: String)
}

/**
 * Holds this install's broker-write grant while the operator is signed in.
 *
 * Mints on sign-in, renews [RENEW_LEAD_MS] before expiry naming the grant it
 * replaces, and after a failed mint or renewal retries every [RETRY_MS]. A
 * grant is unproven until a write under it is accepted ([confirmWrite]).
 * [release] revokes on the server and forgets the secret; a mint still in
 * flight at release is revoked when it completes instead of being stored.
 */
@Singleton
class GrantManager @Inject constructor(
    private val gateway: ConvexGateway,
    auth: CloudAuthState,
    private val clock: WallClock,
    @param:CloudScope scope: CoroutineScope,
) : GrantSource {

    private val _state = MutableStateFlow(GrantState())
    override val state: StateFlow<GrantState> = _state.asStateFlow()

    @Volatile
    private var released = false
    private val releaseLock = Any()

    init {
        scope.launch {
            combine(auth.active, auth.state) { on, s -> on && s == CloudSessionState.SignedIn }
                .distinctUntilChanged()
                .collectLatest { on ->
                    if (on) {
                        released = false
                        maintain()
                    } else {
                        _state.value = GrantState()
                    }
                }
        }
    }

    private suspend fun maintain() {
        while (!released) {
            val ok = mintOnce()
            val held = _state.value.grant
            val wait = if (ok && held != null) {
                (held.expiresAtMs - RENEW_LEAD_MS - clock.nowMs()).coerceAtLeast(0)
            } else {
                RETRY_MS
            }
            delay(wait)
        }
    }

    private suspend fun mintOnce(): Boolean {
        val previous = _state.value.grant
        _state.update { it.copy(minting = true) }
        return try {
            val args = previous?.let { mapOf("replaces" to it.principal) } ?: emptyMap()
            val json = gateway.action("cmdMqttControlGrants:mint", args)
            val r = CloudJson.decodeFromJsonElement(MintResult.serializer(), json)
            val grant = HeldGrant(r.principal, r.secret, r.deviceIds, r.expiresAt.toLong(), writeConfirmed = false)
            // Store only while still signed in; a mint that lands after [release] is
            // revoked instead, so no live grant outlives sign-out.
            val stored = synchronized(releaseLock) {
                if (released) {
                    false
                } else {
                    _state.value = GrantState(grant = grant)
                    true
                }
            }
            if (!stored) runCatching { gateway.mutation("cmdMqttControlGrants:revoke") }
            stored
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val now = clock.nowMs()
            synchronized(releaseLock) {
                if (!released) {
                    _state.update { s ->
                        val live = s.grant?.takeIf { it.expiresAtMs > now }
                        GrantState(grant = live, minting = false, renewalFailed = live != null, error = e.message ?: "Grant unavailable")
                    }
                }
            }
            false
        }
    }

    override suspend fun confirmWrite(principal: String) {
        if (_state.value.grant?.principal != principal || _state.value.grant?.writeConfirmed == true) return
        gateway.mutation("cmdMqttControlGrants:confirmWrite", mapOf("principal" to principal))
        _state.update { s ->
            if (s.grant?.principal == principal) s.copy(grant = s.grant.copy(writeConfirmed = true)) else s
        }
    }

    /** Revokes every grant on the server (sign-out) and forgets the secret. */
    suspend fun release() {
        val held = synchronized(releaseLock) {
            released = true
            _state.value.grant.also { _state.value = GrantState() }
        }
        if (held != null) {
            runCatching { gateway.mutation("cmdMqttControlGrants:revoke") }
        }
    }

    @Serializable
    private data class MintResult(
        val principal: String,
        val secret: String,
        val deviceIds: List<String> = emptyList(),
        val expiresAt: Double,
    )

    companion object {
        const val RENEW_LEAD_MS = 10 * 60 * 1000L
        const val RETRY_MS = 60 * 1000L
    }
}
