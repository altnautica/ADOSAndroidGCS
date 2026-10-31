package com.altnautica.gcs.core.demo.di

import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.demo.DemoNodeSource
import com.altnautica.gcs.core.demo.DemoTransportProvider
import com.altnautica.gcs.core.demo.node.DemoNodeApis
import com.altnautica.gcs.core.demo.video.DemoVideoFactory
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.video.VideoSourceFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Demo mode joins the app only through the extension-point sets. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DemoModule {

    @Binds
    @IntoSet
    abstract fun nodeSource(source: DemoNodeSource): NodeSource

    @Binds
    @IntoSet
    abstract fun transport(provider: DemoTransportProvider): TransportProvider

    @Binds
    @IntoSet
    abstract fun video(factory: DemoVideoFactory): VideoSourceFactory

    @Binds
    @IntoSet
    abstract fun nodeApis(apis: DemoNodeApis): LocalNodeApis
}
