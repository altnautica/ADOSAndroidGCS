package com.altnautica.gcs.data.di

import com.altnautica.gcs.data.video.ModeDetector
import com.altnautica.gcs.data.video.VideoEnvironment
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the hardware/network seam the video fallback decision is written
 * against, so the decision can be driven with a fake in a JVM test while
 * production keeps the real detector.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VideoModule {

    @Binds
    @Singleton
    abstract fun bindVideoEnvironment(detector: ModeDetector): VideoEnvironment
}
