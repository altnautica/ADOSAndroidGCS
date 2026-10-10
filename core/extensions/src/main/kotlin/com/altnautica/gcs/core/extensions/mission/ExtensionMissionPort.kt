package com.altnautica.gcs.core.extensions.mission

import com.altnautica.gcs.core.model.DeviceId
import kotlinx.serialization.json.JsonElement

/** Asks the operator; true when approved. */
fun interface MissionApproval {
    suspend fun ask(title: String, body: String, critical: Boolean): Boolean
}

/** `mission.read` / `mission.write` for extensions bound to a node. */
interface ExtensionMissionPort {
    /** The vehicle's mission as extension waypoints (`{waypoints, progress, currentWaypoint}`). */
    suspend fun read(node: DeviceId): JsonElement

    /** Validate, confirm with the operator, then upload; answers `{ok, error?}`. */
    suspend fun write(pluginId: String, node: DeviceId, args: JsonElement?, approval: MissionApproval): JsonElement
}
