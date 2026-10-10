package com.altnautica.gcs.core.cloud

import com.altnautica.gcs.core.cloud.auth.CloudAuthFailure
import com.altnautica.gcs.core.cloud.auth.CloudSession
import com.altnautica.gcs.core.cloud.auth.CloudSessionState
import com.altnautica.gcs.core.cloud.auth.ConvexAuthPasswordProvider
import com.altnautica.gcs.core.cloud.auth.VaultRefreshTokenStore
import com.altnautica.gcs.core.cloud.convex.AuthBackend
import com.altnautica.gcs.core.cloud.convex.ConvexClients
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.data.vault.VaultUnreadableException
import dev.convex.android.ConvexClientWithAuth
import dev.convex.android.MobileConvexClientInterface
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CloudSession] over the real Convex client and password provider; only the
 * native transport, the vault and the opt-in setting are stand-ins.
 */
class CloudSessionTest {

    private class Backend : AuthBackend {
        val calls = mutableListOf<Map<String, Any?>>()
        var n = 0
        override suspend fun signIn(args: Map<String, Any?>): JsonElement {
            calls += args
            n++
            return buildJsonObject {
                putJsonObject("tokens") {
                    put("token", "jwt-$n")
                    put("refreshToken", "refresh-$n")
                }
            }
        }
    }

    private val config = object : CloudBuildConfig {
        override val convexUrl = "https://cloud.example.com"
    }

    private fun TestScope.session(vault: CredentialVault, backend: Backend): CloudSession {
        val provider = ConvexAuthPasswordProvider(backend, VaultRefreshTokenStore(vault), WallClock { testScheduler.currentTime })
        val transport = mockk<MobileConvexClientInterface>(relaxed = true)
        val client = ConvexClientWithAuth(config.convexUrl, provider, backgroundScope) { _, _, _ -> transport }
        val clients = mockk<ConvexClients> {
            every { authed } returns client
            every { this@mockk.provider } returns provider
        }
        val settings = mockk<CloudSettings> { every { enabled } returns flowOf(true) }
        return CloudSession(mockk(relaxed = true), config, settings, clients, vault, backgroundScope)
    }

    private val vaultError = CloudSessionState.Error(CloudAuthFailure.VaultUnavailable)

    @Test
    fun unreadableVaultOnActivationIsAVisibleErrorThatRetryClears() = runTest {
        var readable = false
        val vault = mockk<CredentialVault> {
            coEvery { requireReadable() } answers {
                if (!readable) throw VaultUnreadableException(RuntimeException("keystore busy"))
            }
            // The lenient read cannot tell "unreadable" from "absent".
            coEvery { cloudRefreshToken() } answers { if (readable) "refresh-0" else null }
            coEvery { retryRead() } answers { readable }
            coEvery { putCloudRefreshToken(any()) } just Runs
        }
        val backend = Backend()
        val session = session(vault, backend)

        runCurrent()
        assertEquals(vaultError, session.state.value)
        assertEquals(0, backend.calls.size)

        readable = true
        session.retry()
        runCurrent()
        assertEquals(CloudSessionState.SignedIn, session.state.value)
        assertEquals(mapOf("refreshToken" to "refresh-0"), backend.calls.single())
    }

    @Test
    fun signOutTheVaultCannotWipeStaysVisibleUntilRetryWipes() = runTest {
        var refuseWrites = false
        val stored = mutableListOf<String?>()
        val vault = mockk<CredentialVault> {
            coEvery { requireReadable() } just Runs
            coEvery { cloudRefreshToken() } returns "refresh-0"
            coEvery { retryRead() } returns true
            coEvery { putCloudRefreshToken(any()) } answers {
                if (refuseWrites) throw VaultUnreadableException(RuntimeException("keystore busy"))
                stored += firstArg<String?>()
            }
        }
        val session = session(vault, Backend())
        runCurrent()
        assertEquals(CloudSessionState.SignedIn, session.state.value)

        refuseWrites = true
        session.signOut()
        runCurrent()
        assertEquals(vaultError, session.state.value)
        assertEquals(listOf<String?>("refresh-1"), stored)

        refuseWrites = false
        session.retry()
        runCurrent()
        assertEquals(CloudSessionState.SignedOut, session.state.value)
        assertEquals(listOf("refresh-1", null), stored)
    }
}
