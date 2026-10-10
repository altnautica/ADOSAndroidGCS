package com.altnautica.gcs.core.video.di

import com.altnautica.gcs.core.video.VideoScope
import com.altnautica.gcs.core.video.VideoSourceFactory
import com.altnautica.gcs.core.video.demo.DemoVideoFactory
import com.altnautica.gcs.core.video.whep.WhepVideoFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class VideoModule {

    @Binds
    @IntoSet
    abstract fun whep(factory: WhepVideoFactory): VideoSourceFactory

    @Binds
    @IntoSet
    abstract fun demo(factory: DemoVideoFactory): VideoSourceFactory

    companion object {
        @Provides
        @Singleton
        @VideoScope
        fun videoScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
