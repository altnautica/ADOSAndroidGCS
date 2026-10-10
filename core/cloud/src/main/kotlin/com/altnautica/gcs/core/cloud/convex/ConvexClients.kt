package com.altnautica.gcs.core.cloud.convex

import com.altnautica.gcs.core.cloud.CloudBuildConfig
import com.altnautica.gcs.core.cloud.WallClock
import com.altnautica.gcs.core.cloud.auth.ConvexAuthPasswordProvider
import com.altnautica.gcs.core.cloud.auth.ConvexAuthSession
import com.altnautica.gcs.core.cloud.auth.VaultRefreshTokenStore
import com.altnautica.gcs.core.cloud.configured
import com.altnautica.gcs.core.data.vault.CredentialVault
import dev.convex.android.ConvexClient
import dev.convex.android.ConvexClientWithAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two Convex clients, created on first use so a build without a backend,
 * or an operator who never enables the cloud, never loads the native client.
 *
 * Sign-in actions run on a separate unauthenticated client: refreshing from
 * inside the authenticated client's own token fetch would wait on itself.
 */
@Singleton
class ConvexClients @Inject constructor(
    private val config: CloudBuildConfig,
    private val vault: CredentialVault,
) {
    private val anon: ConvexClient? by lazy {
        if (config.configured) ConvexClient(config.convexUrl) else null
    }

    val authBackend: AuthBackend = object : AuthBackend {
        override suspend fun signIn(args: Map<String, Any?>): JsonElement =
            (anon ?: throw CloudUnavailableException(NOT_CONFIGURED)).action<JsonElement>("auth:signIn", args)
    }

    val provider: ConvexAuthPasswordProvider by lazy {
        ConvexAuthPasswordProvider(authBackend, VaultRefreshTokenStore(vault), WallClock.System)
    }

    val authed: ConvexClientWithAuth<ConvexAuthSession>? by lazy {
        if (config.configured) ConvexClientWithAuth(config.convexUrl, provider) else null
    }

    companion object {
        const val NOT_CONFIGURED = "Cloud is not configured in this build"
    }
}

/** [ConvexGateway] over the authenticated client. */
@Singleton
class ConvexClientGateway @Inject constructor(
    private val clients: ConvexClients,
) : ConvexGateway {

    override fun subscribe(name: String, args: Map<String, Any?>): Flow<Result<JsonElement>> {
        val client = clients.authed
            ?: return flowOf(Result.failure(CloudUnavailableException(ConvexClients.NOT_CONFIGURED)))
        return client.subscribe<JsonElement>(name, args)
    }

    override suspend fun action(name: String, args: Map<String, Any?>): JsonElement =
        client().action<JsonElement>(name, args)

    override suspend fun mutation(name: String, args: Map<String, Any?>): JsonElement =
        client().mutation<JsonElement>(name, args)

    private fun client() = clients.authed ?: throw CloudUnavailableException(ConvexClients.NOT_CONFIGURED)
}
