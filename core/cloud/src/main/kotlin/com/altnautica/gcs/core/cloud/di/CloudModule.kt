package com.altnautica.gcs.core.cloud.di

import com.altnautica.gcs.core.cloud.CloudScope
import com.altnautica.gcs.core.cloud.WallClock
import com.altnautica.gcs.core.cloud.auth.CloudAuthState
import com.altnautica.gcs.core.cloud.auth.CloudSession
import com.altnautica.gcs.core.cloud.command.CloudQueuedCommandSink
import com.altnautica.gcs.core.cloud.config.CloudConfigRepository
import com.altnautica.gcs.core.cloud.convex.ConvexClientGateway
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import com.altnautica.gcs.core.cloud.grant.GrantManager
import com.altnautica.gcs.core.cloud.grant.GrantSource
import com.altnautica.gcs.core.cloud.link.CloudMavlinkTransportProvider
import com.altnautica.gcs.core.cloud.mqtt.CloudMqttLink
import com.altnautica.gcs.core.cloud.mqtt.HiveMqPortFactory
import com.altnautica.gcs.core.cloud.mqtt.MqttPortFactory
import com.altnautica.gcs.core.cloud.node.CloudNodeSource
import com.altnautica.gcs.core.cloud.video.CloudP2pVideoFactory
import com.altnautica.gcs.core.cloud.video.CloudRelayVideoFactory
import com.altnautica.gcs.core.link.queue.QueuedCommandSink
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.video.VideoSourceFactory
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

/**
 * Cloud bindings. The application binds [com.altnautica.gcs.core.cloud.CloudBuildConfig]
 * from its build configuration.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CloudModule {

    @Binds
    abstract fun gateway(impl: ConvexClientGateway): ConvexGateway

    @Binds
    abstract fun authState(impl: CloudSession): CloudAuthState

    @Binds
    abstract fun grants(impl: GrantManager): GrantSource

    @Binds
    abstract fun mqttPorts(impl: HiveMqPortFactory): MqttPortFactory

    @Binds
    @IntoSet
    abstract fun nodeSource(impl: CloudNodeSource): NodeSource

    @Binds
    @IntoSet
    abstract fun transport(impl: CloudMavlinkTransportProvider): TransportProvider

    @Binds
    @IntoSet
    abstract fun queueSink(impl: CloudQueuedCommandSink): QueuedCommandSink

    @Binds
    @IntoSet
    abstract fun p2pVideo(impl: CloudP2pVideoFactory): VideoSourceFactory

    @Binds
    @IntoSet
    abstract fun relayVideo(impl: CloudRelayVideoFactory): VideoSourceFactory

    companion object {
        @Provides
        @Singleton
        @CloudScope
        fun cloudScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Provides
        fun wallClock(): WallClock = WallClock.System

        @Provides
        @Singleton
        fun mqttLink(
            ports: MqttPortFactory,
            config: CloudConfigRepository,
            grants: GrantSource,
            @CloudScope scope: CoroutineScope,
        ): CloudMqttLink = CloudMqttLink(ports, { config.current().mqttBrokerUrl }, grants, scope)
    }
}
