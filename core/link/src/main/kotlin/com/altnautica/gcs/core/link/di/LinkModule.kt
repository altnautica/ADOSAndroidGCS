package com.altnautica.gcs.core.link.di

import android.os.SystemClock
import com.altnautica.gcs.core.link.direct.DirectFcTransportProvider
import com.altnautica.gcs.core.link.direct.DirectLinkSource
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.queue.QueuedCommandSink
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.model.VehicleProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-lifetime scope that owns vehicle links. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LinkScope

@Module
@InstallIn(SingletonComponent::class)
abstract class LinkModule {

    @Multibinds
    abstract fun nodeSources(): Set<NodeSource>

    @Multibinds
    abstract fun transportProviders(): Set<TransportProvider>

    @Multibinds
    abstract fun queuedCommandSinks(): Set<QueuedCommandSink>

    @Binds
    @IntoSet
    abstract fun directLinkSource(source: DirectLinkSource): NodeSource

    @Binds
    @IntoSet
    abstract fun directFcTransports(provider: DirectFcTransportProvider): TransportProvider

    @Binds
    abstract fun vehicleProvider(repository: VehicleRepository): VehicleProvider

    @Binds
    abstract fun vehicleServicesProvider(repository: VehicleRepository): VehicleServicesProvider

    companion object {
        @Provides
        @Singleton
        @LinkScope
        fun linkScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        fun elapsedClock(): ElapsedClock = ElapsedClock { SystemClock.elapsedRealtime() }
    }
}
