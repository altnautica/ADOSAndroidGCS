package com.altnautica.gcs.core.agent.di

import com.altnautica.gcs.core.agent.mavlink.AgentMavlinkTransportProvider
import com.altnautica.gcs.core.agent.node.LanNodeSource
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.TransportProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentHttpModule {

    /**
     * The base client every agent call derives from (one connection pool).
     * Nothing logs bodies or headers: they carry passphrases and node keys.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder().build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AgentBindingsModule {

    @Binds
    @IntoSet
    abstract fun bindLanNodeSource(source: LanNodeSource): NodeSource

    @Binds
    @IntoSet
    abstract fun bindAgentMavlinkTransport(provider: AgentMavlinkTransportProvider): TransportProvider
}
