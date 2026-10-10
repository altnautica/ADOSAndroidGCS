package com.altnautica.gcs.core.cloud.auth

import android.content.Context
import com.altnautica.gcs.core.cloud.WallClock
import com.altnautica.gcs.core.cloud.convex.AuthBackend
import com.altnautica.gcs.core.cloud.convex.CloudJson
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import dev.convex.android.AuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** The token pair Convex Auth issues; [token] is the short-lived identity token. */
data class ConvexAuthSession(val token: String, val refreshToken: String)

/** What the next interactive login submits. */
sealed interface PasswordFlow {
    data class SignIn(val email: String, val password: String) : PasswordFlow
    data class SignUp(val fullName: String, val email: String, val password: String) : PasswordFlow
}

/**
 * Where the long-lived refresh token is kept between launches. Both calls
 * throw [VaultUnreadableException] while the store cannot be read; [get]
 * returns null only when no token is stored.
 */
interface RefreshTokenStore {
    suspend fun get(): String?
    suspend fun put(token: String?)
}

class VaultRefreshTokenStore(private val vault: CredentialVault) : RefreshTokenStore {
    override suspend fun get(): String? {
        vault.requireReadable()
        return vault.cloudRefreshToken()
    }

    override suspend fun put(token: String?) = vault.putCloudRefreshToken(token)
}

/** Why a sign-in failed, for the operator. */
enum class CloudAuthFailure {
    InvalidCredentials,
    Network,
    RateLimited,

    /** This phone's credential store could not be read; trying again may succeed. */
    VaultUnavailable,
}

class CloudAuthException(val failure: CloudAuthFailure, message: String) : Exception(message)

/**
 * Convex Auth's password provider for the Convex client.
 *
 * - [login] runs the interactive flow set in [pending] (`auth:signIn` with
 *   `provider: "password"`).
 * - [loginFromCache] exchanges the stored refresh token for a new pair. Calls
 *   are serialised, and a refresh completed in the last [REUSE_WINDOW_MS] is
 *   handed to later callers instead of spending the refresh token again
 *   (Convex Auth rotates it on every use, so two concurrent refreshes would
 *   invalidate each other). On failure the client is told the identity is
 *   gone with `onIdToken(null)`.
 * - [logout] wipes the stored pair; the server-side sign-out and grant release
 *   happen in [CloudSession] before the client calls this.
 *
 * The identity token is held in memory only; the refresh token is sealed in
 * the credential vault. When the vault cannot be read or written, every call
 * fails with [CloudAuthFailure.VaultUnavailable] instead of throwing. A
 * rotated refresh token the vault refused is kept in memory, so the next
 * [loginFromCache] spends it (the stored one is already spent) and tries to
 * store its successor.
 */
class ConvexAuthPasswordProvider(
    private val backend: AuthBackend,
    private val tokens: RefreshTokenStore,
    private val clock: WallClock,
) : AuthProvider<ConvexAuthSession> {

    @Volatile
    var pending: PasswordFlow? = null

    private val lock = Mutex()
    private var last: ConvexAuthSession? = null
    private var lastAtMs = Long.MIN_VALUE

    /** A refresh token the server issued that the vault refused to store. */
    @Volatile
    private var unsaved: String? = null

    /** True while a rotated refresh token is held in memory only, waiting to be stored. */
    val holdsUnsavedToken: Boolean get() = unsaved != null

    override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<ConvexAuthSession> {
        val flow = pending ?: return Result.failure(IllegalStateException("No credentials to sign in with"))
        pending = null
        return lock.withLock {
            attempt {
                exchange(signInArgs(flow))
            }
        }
    }

    override suspend fun loginFromCache(onIdToken: (String?) -> Unit): Result<ConvexAuthSession> {
        val result = lock.withLock {
            val reuse = last
            if (reuse != null && clock.nowMs() - lastAtMs < REUSE_WINDOW_MS) {
                Result.success(reuse)
            } else {
                attempt {
                    val refresh = unsaved
                        ?: tokens.get()
                        ?: throw CloudAuthException(CloudAuthFailure.InvalidCredentials, "Not signed in")
                    exchange(mapOf("refreshToken" to refresh))
                }
            }
        }
        result.onSuccess { onIdToken(it.token) }.onFailure { onIdToken(null) }
        return result
    }

    override suspend fun logout(context: Context): Result<Void?> = lock.withLock {
        last = null
        lastAtMs = Long.MIN_VALUE
        unsaved = null
        try {
            tokens.put(null)
            Result.success(null)
        } catch (e: VaultUnreadableException) {
            // The stored token is still there; say so rather than report a sign-out.
            Result.failure(vaultUnavailable())
        }
    }

    override fun extractIdToken(authResult: ConvexAuthSession): String = authResult.token

    private suspend fun exchange(args: Map<String, Any?>): ConvexAuthSession {
        val session = parseTokens(backend.signIn(args))
        // Convex Auth has already rotated the refresh token: if it cannot be stored,
        // keep it for the next attempt rather than fall back to the spent stored one.
        unsaved = session.refreshToken
        try {
            tokens.put(session.refreshToken)
        } catch (e: VaultUnreadableException) {
            throw vaultUnavailable()
        }
        unsaved = null
        last = session
        lastAtMs = clock.nowMs()
        return session
    }

    private suspend fun attempt(block: suspend () -> ConvexAuthSession): Result<ConvexAuthSession> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: CloudAuthException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(classify(e))
    }

    companion object {
        const val REUSE_WINDOW_MS = 10_000L

        fun signInArgs(flow: PasswordFlow): Map<String, Any?> = when (flow) {
            is PasswordFlow.SignIn -> mapOf(
                "provider" to "password",
                "params" to mapOf("email" to flow.email, "password" to flow.password, "flow" to "signIn"),
            )
            is PasswordFlow.SignUp -> mapOf(
                "provider" to "password",
                "params" to mapOf(
                    "email" to flow.email,
                    "password" to flow.password,
                    "flow" to "signUp",
                    "fullName" to flow.fullName,
                ),
            )
        }

        @Serializable
        private data class SignInResult(val tokens: Tokens? = null)

        @Serializable
        private data class Tokens(val token: String, val refreshToken: String)

        internal fun parseTokens(json: JsonElement): ConvexAuthSession {
            if (json is JsonNull || json !is JsonObject) {
                throw CloudAuthException(CloudAuthFailure.InvalidCredentials, "Sign-in returned no session")
            }
            val tokens = CloudJson.decodeFromJsonElement(SignInResult.serializer(), json).tokens
                ?: throw CloudAuthException(CloudAuthFailure.InvalidCredentials, "Sign-in returned no session")
            return ConvexAuthSession(tokens.token, tokens.refreshToken)
        }

        internal fun vaultUnavailable() =
            CloudAuthException(CloudAuthFailure.VaultUnavailable, "This phone's credential store could not be read; try again")

        /** Maps a server, transport or credential-store error onto what the operator can act on. */
        fun classify(e: Throwable): CloudAuthException {
            if (e is CloudAuthException) return e
            if (e is VaultUnreadableException) return vaultUnavailable()
            val text = buildString {
                append(e.message.orEmpty())
                append(' ')
                append(e.javaClass.simpleName)
                (e as? dev.convex.android.ConvexError)?.data?.let { append(' ').append(it) }
            }
            return when {
                RATE.any { text.contains(it, ignoreCase = true) } ->
                    CloudAuthException(CloudAuthFailure.RateLimited, "Too many attempts; try again later")
                INVALID.any { text.contains(it, ignoreCase = true) } ->
                    CloudAuthException(CloudAuthFailure.InvalidCredentials, "Email or password is incorrect")
                else -> CloudAuthException(CloudAuthFailure.Network, e.message ?: "Cloud unreachable")
            }
        }

        private val RATE = listOf("TooManyFailedAttempts", "rate_limited", "rate limit")
        private val INVALID = listOf("InvalidSecret", "InvalidAccountId", "Invalid credentials", "Invalid password", "InvalidRefreshToken")
    }
}
