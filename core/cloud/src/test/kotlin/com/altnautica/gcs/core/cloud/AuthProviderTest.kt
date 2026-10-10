package com.altnautica.gcs.core.cloud

import com.altnautica.gcs.core.cloud.auth.CloudAuthException
import com.altnautica.gcs.core.cloud.auth.CloudAuthFailure
import com.altnautica.gcs.core.cloud.auth.ConvexAuthPasswordProvider
import com.altnautica.gcs.core.cloud.auth.PasswordFlow
import com.altnautica.gcs.core.cloud.auth.RefreshTokenStore
import com.altnautica.gcs.core.cloud.convex.AuthBackend
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthProviderTest {

    private class MemoryTokens(var value: String?) : RefreshTokenStore {
        val writes = mutableListOf<String?>()
        override suspend fun get() = value
        override suspend fun put(token: String?) {
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
