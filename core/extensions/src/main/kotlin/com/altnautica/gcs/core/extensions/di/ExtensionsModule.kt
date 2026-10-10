package com.altnautica.gcs.core.extensions.di

import com.altnautica.gcs.core.extensions.data.AgentExtensionApiProvider
import com.altnautica.gcs.core.extensions.data.ExtensionApiProvider
import com.altnautica.gcs.core.extensions.data.ExtensionScope
import com.altnautica.gcs.core.extensions.mission.ExtensionMissionPort
import com.altnautica.gcs.core.extensions.mission.VehicleMissionPort
import com.altnautica.gcs.core.extensions.skills.ExtensionSkillContributor
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.SkillContributor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ExtensionsModule {

    @Binds
    abstract fun bindApiProvider(impl: AgentExtensionApiProvider): ExtensionApiProvider

    @Binds
    abstract fun bindMissionPort(impl: VehicleMissionPort): ExtensionMissionPort

    @Binds
    @IntoSet
    abstract fun bindSkillContributor(impl: ExtensionSkillContributor): SkillContributor

    /** Node sources are contributed by the link lanes; an app with none still builds. */
    @Multibinds
    abstract fun nodeSources(): Set<NodeSource>

    companion object {
        @Provides
        @Singleton
        @ExtensionScope
        fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
