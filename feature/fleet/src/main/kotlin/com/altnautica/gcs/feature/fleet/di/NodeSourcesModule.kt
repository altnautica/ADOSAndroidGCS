package com.altnautica.gcs.feature.fleet.di

import com.altnautica.gcs.core.model.NodeSource
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Declares the node-source set so the graph builds even before any link family contributes. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NodeSourcesModule {
    @Multibinds
    abstract fun nodeSources(): Set<NodeSource>
}
