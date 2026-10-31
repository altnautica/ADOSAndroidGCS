package com.altnautica.gcs.core.skills.input

import android.os.SystemClock
import com.altnautica.gcs.core.model.SkillContributor
import com.altnautica.gcs.core.skills.MonotonicClock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-lifetime main-thread scope that owns controller hardware (battery, rumble, light bar). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InputScope

@Module
@InstallIn(SingletonComponent::class)
abstract class InputModule {
    @Binds
    abstract fun bindInputRouter(router: AdosInputRouter): InputRouter

    /** Declares the contributor set so it resolves (empty) before any extension source binds into it. */
    @Multibinds
    abstract fun skillContributors(): Set<SkillContributor>

    companion object {
        @Provides
        fun provideMonotonicClock(): MonotonicClock = MonotonicClock { SystemClock.elapsedRealtime() }

        @Provides
        @Singleton
        @InputScope
        fun inputScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
