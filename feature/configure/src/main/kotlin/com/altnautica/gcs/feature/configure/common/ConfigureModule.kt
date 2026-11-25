package com.altnautica.gcs.feature.configure.common

import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.ParamDocs
import com.altnautica.gcs.feature.configure.params.ParamDocsFromMetadata
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map

@Module
@InstallIn(SingletonComponent::class)
object ConfigureModule {
    @Provides
    fun provideConfigureTargetSource(services: VehicleServicesProvider): ConfigureTargetSource =
        ConfigureTargetSource { id ->
            services.services(id).map { s -> s?.let { ConfigureTarget(it.vehicle, it.params, it.calibration) } }
        }

    @Provides
    fun provideParamDocs(impl: ParamDocsFromMetadata): ParamDocs = impl
}
