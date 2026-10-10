package com.altnautica.gcs.core.cloud

import com.altnautica.gcs.core.cloud.auth.CloudAuthException
import com.altnautica.gcs.core.cloud.auth.CloudAuthFailure
import com.altnautica.gcs.core.cloud.auth.ConvexAuthPasswordProvider
import com.altnautica.gcs.core.cloud.auth.PasswordFlow
import com.altnautica.gcs.core.cloud.auth.RefreshTokenStore
import com.altnautica.gcs.core.cloud.auth.VaultRefreshTokenStore
import com.altnautica.gcs.core.cloud.convex.AuthBackend
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthProviderTest {

    private class MemoryTokens(var value: String?) : RefreshTokenStore {
        val writes = mutableListOf<String?>()

        /** While set, the store behaves like a vault that cannot be read: every call throws. */
        var unreadable = false

        /** While set, reads serve the last good copy but writes are refused. */
        var refuseWrites = false
        override suspend fun get(): String? {
            if (unreadable) throw VaultUnreadableException(RuntimeException("keystore busy"))
            return value
        }
        override suspend fun put(token: String?) {
            if (unreadable || refuseWrites) throw VaultUnreadableException(RuntimeException("keystore busy"))
            value = token
            writes += token
        }
    }

    private class Backend : AuthBackend {
        val calls = mutableListOf<Map<String, Any?>>()
        var fail: Exception? = null
        var n = 0
        override suspend fun signIn(args: Map<String, Any?>): JsonElement {
            calls += args
            delay(100)
            fail?.let { throw it }
            n++
            return buildJsonObject {
                putJsonObject("tokens") {
                    put("token", "jwt-$n")
                    put("refreshToken", "refresh-$n")
                }
            }
        }
    }

    @Test
    fun concurrentRefreshesShareOneExchangeAndStoreTheRotatedPair() = runTest {
        val backend = Backend()
        val tokens = MemoryTokens("refresh-0")
        val provider = ConvexAuthPasswordProvider(backend, tokens, WallClock { testScheduler.currentTime })
        val seen = mutableListOf<String?>()

        val results = (1..5).map { async { provider.loginFromCache { seen += it } } }.awaitAll()

        assertEquals(1, backend.calls.size)
        assertEquals(mapOf("refreshToken" to "refresh-0"), backend.calls.single())
        assertTrue(results.all { it.getOrNull()?.token == "jwt-1" })
        assertEquals("refresh-1", tokens.value)
        assertEquals(List(5) { "jwt-1" }, seen)

        // Past the reuse window the next refresh spends the rotated token.
        delay(ConvexAuthPasswordProvider.REUSE_WINDOW_MS)
        provider.loginFromCache { }
        assertEquals(2, backend.calls.size)
        assertEquals(mapOf("refreshToken" to "refresh-1"), backend.calls.last())
        assertEquals("refresh-2", tokens.value)
    }

    @Test
    fun failedRefreshClearsTheIdentity() = runTest {
        val backend = Backend().apply { fail = RuntimeException("InvalidRefreshToken") }
        val provider = ConvexAuthPasswordProvider(backend, MemoryTokens("stale"), WallClock { testScheduler.currentTime })
        val seen = mutableListOf<String?>()

        val result = provider.loginFromCache { seen += it }

        assertTrue(result.isFailure)
        assertEquals(listOf<String?>(null), seen)
        assertEquals(CloudAuthFailure.InvalidCredentials, (result.exceptionOrNull() as CloudAuthException).failure)
    }

    @Test
    fun noStoredTokenFailsWithoutCallingTheServer() = runTest {
        val backend = Backend()
        val provider = ConvexAuthPasswordProvider(backend, MemoryTokens(null), WallClock { testScheduler.currentTime })
        val seen = mutableListOf<String?>()
        assertTrue(provider.loginFromCache { seen += it }.isFailure)
        assertEquals(0, backend.calls.size)
        assertEquals(listOf<String?>(null), seen)
    }

    private fun unreadableVault() = mockk<CredentialVault> {
        coEvery { requireReadable() } throws VaultUnreadableException(RuntimeException("keystore busy"))
        // The lenient read cannot tell "unreadable" from "absent".
        coEvery { cloudRefreshToken() } returns null
        coEvery { putCloudRefreshToken(any()) } throws VaultUnreadableException(RuntimeException("keystore busy"))
    }

    private fun CloudAuthFailure?.of(result: Result<*>) = assertEquals(this, (result.exceptionOrNull() as? CloudAuthException)?.failure)

    @Test
    fun unreadableVaultFailsRefreshAsVaultUnavailableWithoutCallingTheServer() = runTest {
        val backend = Backend()
        val provider = ConvexAuthPasswordProvider(backend, VaultRefreshTokenStore(unreadableVault()), WallClock { testScheduler.currentTime })
        val seen = mutableListOf<String?>()

        val result = provider.loginFromCache { seen += it }

        CloudAuthFailure.VaultUnavailable.of(result)
        assertEquals(0, backend.calls.size)
        assertEquals(listOf<String?>(null), seen)
    }

    @Test
    fun rotatedTokenTheVaultRefusedIsSpentOnTheNextRefreshAndStoredOnceItWorks() = runTest {
        val backend = Backend()
        val tokens = MemoryTokens("refresh-0").apply { refuseWrites = true }
        val provider = ConvexAuthPasswordProvider(backend, tokens, WallClock { testScheduler.currentTime })

        // The server rotates the token but the vault refuses the new one.
        CloudAuthFailure.VaultUnavailable.of(provider.loginFromCache { })
        assertEquals(mapOf("refreshToken" to "refresh-0"), backend.calls.single())
        assertEquals("refresh-0", tokens.value)
        assertTrue(provider.holdsUnsavedToken)

        // Still refused (even reads now): the retry spends the in-memory token, never the spent stored one.
        tokens.unreadable = true
        CloudAuthFailure.VaultUnavailable.of(provider.loginFromCache { })
        assertEquals(mapOf("refreshToken" to "refresh-1"), backend.calls.last())

        tokens.unreadable = false
        tokens.refuseWrites = false
        val ok = provider.loginFromCache { }
        assertEquals("jwt-3", ok.getOrNull()?.token)
        assertEquals(mapOf("refreshToken" to "refresh-2"), backend.calls.last())
        assertEquals("refresh-3", tokens.value)
        assertFalse(provider.holdsUnsavedToken)
    }

    @Test
    fun interactiveSignInTheVaultRefusesIsVaultUnavailableNotACredentialError() = runTest {
        val backend = Backend()
        val tokens = MemoryTokens(null).apply { refuseWrites = true }
        val provider = ConvexAuthPasswordProvider(backend, tokens, WallClock { testScheduler.currentTime })
        provider.pending = PasswordFlow.SignIn("a@b.c", "pw")

        CloudAuthFailure.VaultUnavailable.of(provider.login(mockk(relaxed = true)) { })
        assertTrue(provider.holdsUnsavedToken)

        tokens.refuseWrites = false
        assertTrue(provider.loginFromCache { }.isSuccess)
        assertEquals(mapOf("refreshToken" to "refresh-1"), backend.calls.last())
        assertEquals("refresh-2", tokens.value)
    }

    @Test
    fun logoutTheVaultRefusesReportsVaultUnavailableInsteadOfThrowing() = runTest {
        val vault = unreadableVault()
        val provider = ConvexAuthPasswordProvider(Backend(), VaultRefreshTokenStore(vault), WallClock { testScheduler.currentTime })

        val result = provider.logout(mockk(relaxed = true))

        CloudAuthFailure.VaultUnavailable.of(result)
        coEvery { vault.putCloudRefreshToken(null) } returns Unit
        assertTrue(provider.logout(mockk(relaxed = true)).isSuccess)
    }

    @Test
    fun signInArgumentsMatchThePasswordProvider() {
        assertEquals(
            mapOf("provider" to "password", "params" to mapOf("email" to "a@b.c", "password" to "pw", "flow" to "signIn")),
            ConvexAuthPasswordProvider.signInArgs(PasswordFlow.SignIn("a@b.c", "pw")),
        )
        val up = ConvexAuthPasswordProvider.signInArgs(PasswordFlow.SignUp("Ann", "a@b.c", "pw"))
        assertEquals("signUp", (up["params"] as Map<*, *>)["flow"])
        assertEquals("Ann", (up["params"] as Map<*, *>)["fullName"])
    }

    @Test
    fun errorsClassifyForTheOperator() {
        assertEquals(CloudAuthFailure.InvalidCredentials, ConvexAuthPasswordProvider.classify(RuntimeException("InvalidSecret")).failure)
        assertEquals(CloudAuthFailure.RateLimited, ConvexAuthPasswordProvider.classify(RuntimeException("TooManyFailedAttempts")).failure)
        assertEquals(CloudAuthFailure.Network, ConvexAuthPasswordProvider.classify(java.io.IOException("timeout")).failure)
    }

    @Test
    fun nullSessionIsInvalidCredentials() {
        val e = runCatching { ConvexAuthPasswordProvider.parseTokens(JsonNull) }.exceptionOrNull()
        assertEquals(CloudAuthFailure.InvalidCredentials, (e as CloudAuthException).failure)
        assertNull(runCatching { ConvexAuthPasswordProvider.parseTokens(buildJsonObject { }) }.getOrNull())
    }
}
