package com.altnautica.gcs.core.map.offline

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class OfflineMapModule {
    @Binds
    abstract fun bindOfflineMapRepository(impl: MapLibreOfflineMapRepository): OfflineMapRepository
}
