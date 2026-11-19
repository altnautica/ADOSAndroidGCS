package com.altnautica.gcs.radio.rx.bench

import android.content.Context
import com.altnautica.gcs.radio.rx.AlternateRadio
import com.altnautica.gcs.radio.rx.invite.InviteBundle
import com.altnautica.gcs.radio.rx.link.LinkParams
import com.altnautica.gcs.radio.rx.tx.GroundTxParams
import com.altnautica.gcs.radio.usb.IUsbRadio
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the phone meets the fleet bench's relay. Written by the bench to
 * `<app external files>/bench-relay.json`; with no file the phone uses its
 * USB radio as in a release build.
 */
@Serializable
data class BenchRelayConfig(
    /** The relay node (the bench's ground-station guest). */
    @SerialName("relay_host") val relayHost: String,
    /** This phone's node name on the air; distinct from every guest's. */
    val node: String,
    /** The address the relay sends this node's frames to (the host's forwarder). */
    @SerialName("register_address") val registerAddress: String,
    /** The UDP port frames arrive on inside the phone. */
    @SerialName("listen_port") val listenPort: Int = 15802,
)

/** The bench relay as the phone's radio, when the bench configured one. */
@Singleton
class BenchRelaySource @Inject constructor(@ApplicationContext private val context: Context) : AlternateRadio {

    private fun config(): BenchRelayConfig? {
        val file = File(context.getExternalFilesDir(null), CONFIG_FILE)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<BenchRelayConfig>(file.readText()) }.getOrNull()
    }

    override val available: Boolean get() = config() != null

    /** The bench holds the uplink: it is the only way a phone transmits until the bind and the handover exist. */
    override val holdsUplink: Boolean get() = available

    override fun open(bundle: InviteBundle): IUsbRadio {
        val cfg = requireNotNull(config()) { "no bench relay configured" }
        val ports = bundle.radioPorts
        val rx = bundle.links().flatMap { link ->
            listOf(ports.video, ports.control, ports.auxDown).map { LinkParams.channelId(link.linkId, it) }
        }
        val (control, aux) = GroundTxParams.of(bundle)
        return BenchRelayRadio(cfg, rx, listOf(control.channelId, aux.channelId))
    }

    private companion object {
        const val CONFIG_FILE = "bench-relay.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BenchRelayModule {
    @Binds
    abstract fun alternateRadio(source: BenchRelaySource): AlternateRadio
}
