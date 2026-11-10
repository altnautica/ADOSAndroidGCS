package com.altnautica.gcs.feature.cockpit.tips

import android.content.Context
import com.altnautica.gcs.core.skills.tips.TipsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** The per-device tips store, reached from the flight screen's composition. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TipsEntryPoint {
    fun tips(): TipsRepository
}

/** The app's [TipsRepository]. */
fun tipsRepository(context: Context): TipsRepository =
    EntryPointAccessors.fromApplication(context.applicationContext, TipsEntryPoint::class.java).tips()
