package com.altnautica.gcs.core.cloud.config

import com.altnautica.gcs.core.cloud.CloudScope
import com.altnautica.gcs.core.cloud.auth.CloudAuthState
import com.altnautica.gcs.core.cloud.convex.CloudJson
import com.altnautica.gcs.core.cloud.convex.ConvexGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/** Where the cloud's message broker and video relay live. */
data class CloudEndpoints(val mqttBrokerUrl: String, val videoRelayUrl: String) {
    companion object {
        const val DEFAULT_MQTT_BROKER_URL = "wss://mqtt.altnautica.com/mqtt"
        const val DEFAULT_VIDEO_RELAY_URL = "wss://video.altnautica.com"
        val Default = CloudEndpoints(DEFAULT_MQTT_BROKER_URL, DEFAULT_VIDEO_RELAY_URL)

        /** Server values win; a missing or blank value falls back to the public endpoint. */
        fun fromClientConfig(json: JsonElement): CloudEndpoints {
            val cfg = runCatching { CloudJson.decodeFromJsonElement(ClientConfig.serializer(), json) }.getOrNull()
            return CloudEndpoints(
                mqttBrokerUrl = cfg?.mqttBrokerUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_MQTT_BROKER_URL,
                videoRelayUrl = cfg?.videoRelayUrl?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: DEFAULT_VIDEO_RELAY_URL,
            )
        }
    }
}

@Serializable
internal data class ClientConfig(val mqttBrokerUrl: String? = null, val videoRelayUrl: String? = null)

/** Live client configuration, subscribed only while the cloud is active. */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CloudConfigRepository @Inject constructor(
    private val gateway: ConvexGateway,
    private val auth: CloudAuthState,
    @param:CloudScope scope: CoroutineScope,
) {
    val endpoints: StateFlow<CloudEndpoints> = auth.active
        .flatMapLatest { on ->
            if (!on) {
                flowOf(CloudEndpoints.Default)
            } else {
                gateway.subscribe("clientConfig:getClientConfig").map { r ->
                    r.map(CloudEndpoints::fromClientConfig).getOrDefault(CloudEndpoints.Default)
                }
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), CloudEndpoints.Default)

    /** One fresh read, for a dial that cannot wait for a subscriber to warm up. */
    suspend fun current(): CloudEndpoints {
        if (!auth.active.value) return CloudEndpoints.Default
        return gateway.subscribe("clientConfig:getClientConfig").first()
            .map(CloudEndpoints::fromClientConfig).getOrDefault(CloudEndpoints.Default)
    }
}
