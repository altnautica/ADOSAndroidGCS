package com.altnautica.gcs.feature.cockpit.di

import com.altnautica.gcs.core.model.NodeSource
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

@Module
@InstallIn(SingletonComponent::class)
abstract class CockpitModule {
    /** The cockpit resolves its node from every link source; the set may be empty in a minimal build. */
    @Multibinds
    abstract fun nodeSources(): Set<NodeSource>
}
