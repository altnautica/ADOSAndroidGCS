package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Reach
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A vehicle link currently held open by an observer. */
data class OpenLink(
    val id: DeviceId,
    val reach: Reach,
)

/** Process-wide record of open vehicle links and their newest heartbeat. */
@Singleton
class LinkActivity @Inject constructor() {
    private val _lastHeartbeat = MutableStateFlow<Map<DeviceId, Long>>(emptyMap())

    /** Newest vehicle HEARTBEAT receive time per node, on the elapsed-realtime clock. */
    val lastHeartbeat: StateFlow<Map<DeviceId, Long>> = _lastHeartbeat.asStateFlow()

    private val _openLinks = MutableStateFlow<List<OpenLink>>(emptyList())

    /** Links with an attached transport; empty when nothing is connected. */
    val openLinks: StateFlow<List<OpenLink>> = _openLinks.asStateFlow()

    private val autopilots = MutableStateFlow<Map<DeviceId, Int>>(emptyMap())

    internal fun heartbeat(id: DeviceId, tMs: Long, autopilot: Int) {
        _lastHeartbeat.update { it + (id to tMs) }
        if (autopilots.value[id] != autopilot) autopilots.update { it + (id to autopilot) }
    }

    internal fun opened(id: DeviceId, reach: Reach) {
        _openLinks.update { links -> links.filterNot { it.id == id } + OpenLink(id, reach) }
    }

    internal fun closed(id: DeviceId) {
        _openLinks.update { links -> links.filterNot { it.id == id } }
    }

    /** Firmware learnt from the heartbeat; [FcVariant.Unknown] before the first one. */
    fun variant(id: DeviceId): FcVariant = when (autopilots.value[id]) {
        MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA -> FcVariant.MavlinkArdupilot
        MavAutopilot.MAV_AUTOPILOT_PX4 -> FcVariant.MavlinkPx4
        else -> FcVariant.Unknown
    }
}
