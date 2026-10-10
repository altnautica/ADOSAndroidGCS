package com.altnautica.gcs.core.agent.di

import com.altnautica.gcs.core.agent.http.AgentHostInterceptor
import com.altnautica.gcs.core.agent.pairing.AgentAuthInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    private const val TIMEOUT_SECONDS = 6L

    /**
     * The one HTTP client for agent traffic. Requests are retargeted at the
     * configured agent and carry the pairing key when one is stored. Nothing
     * logs bodies or headers: they carry AP passphrases and the agent's
     * full-authority key.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        hostInterceptor: AgentHostInterceptor,
        authInterceptor: AgentAuthInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(hostInterceptor)
        .addInterceptor(authInterceptor)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Tolerant of fields an agent adds later; omits nulls from request bodies. */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
