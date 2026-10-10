package com.altnautica.gcs.di

import com.altnautica.gcs.BuildConfig
import com.altnautica.gcs.core.cloud.CloudBuildConfig
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.replay.ReplayNodeSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Bindings owned by the app shell. Link-family modules bind their own sources. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @IntoSet
    abstract fun replayNodes(source: ReplayNodeSource): NodeSource

    companion object {
        /** The Convex deployment URL from `local.properties`; empty builds have cloud switched off. */
        @Provides
        fun cloudBuildConfig(): CloudBuildConfig = object : CloudBuildConfig {
            override val convexUrl: String = BuildConfig.CONVEX_URL
        }
    }
}
