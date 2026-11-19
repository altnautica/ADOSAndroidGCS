package com.altnautica.gcs.radio.rx.aux

import com.altnautica.gcs.core.link.fleet.FleetGroundStore
import com.altnautica.gcs.core.link.fleet.FleetRequestLane
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PhoneRadioUplink
import com.altnautica.gcs.core.model.RadioCall
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The phone radio's transmit side for the app: the pilot lane and relayed
 * node-API calls. A relayed call carries a ticket minted from the per-pair
 * secret this phone issued the drone as its fleet's ground; with no secret
 * on file the call is refused here, as the box's relay refuses it, rather
 * than sent for the drone to refuse.
 */
@Singleton
class SessionUplink @Inject constructor(
    private val session: PhoneRadioSession,
    private val fleetGround: FleetGroundStore,
) : PhoneRadioUplink {
    override val transmitting: StateFlow<Boolean> get() = session.transmitting

    override fun hears(node: DeviceId): Boolean = session.hears(node)

    override fun sendPilot(node: DeviceId, body: ByteArray): Boolean = session.lanes.sendPilot(node.value, body)

    override fun pilotFrom(node: DeviceId): Flow<ByteArray> = session.lanes.pilot(node.value)

    override suspend fun call(node: DeviceId, method: String, path: String, contentType: String?, body: ByteArray): RadioCall {
        val secret = fleetGround.fleet.value?.slots?.firstOrNull { it.deviceId == node.value }?.relaySecret
            ?: return RadioCall.Answered(UNAUTHORIZED, NO_SECRET_BODY, emptyList())
        return when (val r = session.lanes.relay.call(node.value, method, path, contentType, body, secret)) {
            is RelayResult.Answered -> RadioCall.Answered(r.response.status, r.response.body, r.response.headers)
            is RelayResult.Failed -> RadioCall.Failed(r.reason.name)
        }
    }

    private companion object {
        const val UNAUTHORIZED = 401
        val NO_SECRET_BODY = """{"error":"E_RELAY_TICKET","reason":"no_secret"}""".toByteArray()
    }
}

/** The fleet's ground calls on the Request lane: slot assignment and the secret offer. */
@Singleton
class SessionFleetLane @Inject constructor(private val session: PhoneRadioSession) : FleetRequestLane {
    override suspend fun request(
        deviceId: String,
        method: String,
        path: String,
        contentType: String?,
        body: ByteArray,
        relaySecretHex: String?,
    ): Int? = when (val r = session.lanes.relay.call(deviceId, method, path, contentType, body, relaySecretHex)) {
        is RelayResult.Answered -> r.response.status
        is RelayResult.Failed -> null
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RadioLanesModule {
    @Binds
    abstract fun uplink(impl: SessionUplink): PhoneRadioUplink

    @Binds
    abstract fun fleetLane(impl: SessionFleetLane): FleetRequestLane
}
