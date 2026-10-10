package com.altnautica.gcs.core.link.queue

import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VehicleCommand

/**
 * Delivers commands to a vehicle that has no live byte link, only a queue
 * (the cloud command queue, for example). Bound into a Hilt set. Results are
 * typically [CommandResult.Queued], never a claim that the vehicle accepted.
 */
interface QueuedCommandSink {
    fun supports(reach: Reach): Boolean

    suspend fun run(node: DeviceId, command: VehicleCommand): CommandResult
}
