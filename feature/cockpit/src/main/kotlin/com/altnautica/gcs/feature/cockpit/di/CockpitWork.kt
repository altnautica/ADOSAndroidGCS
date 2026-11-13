package com.altnautica.gcs.feature.cockpit.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import kotlin.coroutines.CoroutineContext

/**
 * The context the cockpit parses stream frames and reads the data volume in, off the main
 * thread. A cockpit built directly (a unit test) gets an empty context and does that work
 * inline, in its collector's dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CockpitWork

@Module
@InstallIn(SingletonComponent::class)
object CockpitWorkModule {
    @Provides
    @CockpitWork
    fun work(): CoroutineContext = Dispatchers.Default
}
