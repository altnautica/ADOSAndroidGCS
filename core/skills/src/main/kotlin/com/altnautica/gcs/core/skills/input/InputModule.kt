package com.altnautica.gcs.core.skills.input

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InputModule {
    @Binds
    abstract fun bindInputRouter(router: PassThroughInputRouter): InputRouter
}
