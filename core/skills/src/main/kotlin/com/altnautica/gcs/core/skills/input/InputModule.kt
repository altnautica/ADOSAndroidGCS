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
    }
}
