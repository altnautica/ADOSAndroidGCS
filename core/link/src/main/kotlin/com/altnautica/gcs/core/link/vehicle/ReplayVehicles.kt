package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.link.di.LinkScope
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.core.model.VehicleTelemetry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Read-only vehicles decoded from a recorded or otherwise receive-only
 * transport (a tlog replay, for example). While open, the
 * [com.altnautica.gcs.core.model.VehicleProvider] returns the replay for its
 * id; every command is denied with reason [REASON].
 */
@Singleton
class ReplayVehicles @Inject constructor(
    private val clock: ElapsedClock,
    @param:LinkScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val inner = mutableMapOf<DeviceId, MavlinkVehicle>()

    private val _active = MutableStateFlow<Map<DeviceId, Vehicle>>(emptyMap())

    /** Open replays by id. */
    val active: StateFlow<Map<DeviceId, Vehicle>> = _active.asStateFlow()

    /** Decodes [transport] as vehicle [id], replacing any replay already open under that id. */
    suspend fun open(id: DeviceId, transport: MavlinkByteTransport): Vehicle = lock.withLock {
        inner.remove(id)?.shutdown()
        val mavlink = MavlinkVehicle(id, scope, clock, activity = null)
        mavlink.setReach(Reach.Blocked(BlockReason.ReceiveOnly))
        mavlink.link.attach(transport)
        inner[id] = mavlink
        val replay = ReplayVehicle(mavlink)
        _active.update { it + (id to replay) }
        replay
    }

    /** Ends the replay for [id] and closes its transport. */
    suspend fun close(id: DeviceId) = lock.withLock {
        _active.update { it - id }
        inner.remove(id)?.shutdown()
    }

    private class ReplayVehicle(private val source: MavlinkVehicle) : Vehicle {
        override val id: DeviceId get() = source.id
        override val reach: StateFlow<Reach> = MutableStateFlow(Reach.Blocked(BlockReason.ReceiveOnly)).asStateFlow()
        override val telemetry: VehicleTelemetry get() = source.telemetry
        override val control: VehicleControl = object : VehicleControl {
            override val availableModes: StateFlow<List<FlightMode>> = MutableStateFlow(emptyList<FlightMode>()).asStateFlow()
            override suspend fun run(command: VehicleCommand): CommandResult = CommandResult.Denied(REASON)
        }
    }

    companion object {
        const val REASON = "replay"
    }
}
