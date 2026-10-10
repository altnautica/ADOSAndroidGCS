package com.altnautica.gcs.core.extensions.mission

import com.altnautica.gcs.core.link.mission.MissionResult
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Extension mission access over the vehicle's MAVLink mission protocol. */
@Singleton
class VehicleMissionPort @Inject constructor(
    private val services: VehicleServicesProvider,
) : ExtensionMissionPort {

    private suspend fun servicesFor(node: DeviceId): VehicleServices? =
        withTimeoutOrNull(SERVICES_WAIT_MS) { services.services(node).first() }

    private fun failure(error: String) = buildJsonObject {
        put("ok", false)
        put("error", error)
    }

    override suspend fun read(node: DeviceId): JsonElement {
        val s = servicesFor(node) ?: return failure("extension is not bound to a connected drone")
        return when (val r = s.mission.download(MissionType.Mission)) {
            is MissionResult.Success -> buildJsonObject {
                put("waypoints", MissionCodec.collapse(r.value.items))
                put("activeMission", JsonNull)
                val progress = s.vehicle.telemetry.missionProgress.value
                put("progress", progress?.let {
                    buildJsonObject {
                        put("currentSeq", it.currentSeq)
                        put("total", it.total)
                    }
                } ?: JsonNull)
                put("currentWaypoint", progress?.currentSeq?.let(::JsonPrimitive) ?: JsonNull)
            }
            is MissionResult.Failure -> failure("mission download failed: ${r.error}")
        }
    }

    override suspend fun write(pluginId: String, node: DeviceId, args: JsonElement?, approval: MissionApproval): JsonElement {
        val payload = ((args as? JsonObject)?.get("payload") as? JsonObject)?.get("waypoints")
        val waypoints = MissionCodec.sanitize(payload).getOrElse {
            return failure("mission.write rejected: ${it.message}")
        }
        val s = servicesFor(node) ?: return failure("extension is not bound to a connected drone")
        refusal(s)?.let { return failure(it) }
        val frames = waypoints.map { it.frame ?: "relative" }.toSet().joinToString(", ")
        val approved = approval.ask(
            "Extension mission write",
            "$pluginId wants to replace the mission on ${node.value} with ${waypoints.size} waypoints (altitude frame: $frames)",
            false,
        )
        if (!approved) return failure("declined by operator")
        val after = servicesFor(node)
        if (after == null || after !== s) return failure("the drone's flight controller changed while awaiting approval")
        refusal(after)?.let { return failure(it) }
        return when (val r = s.mission.upload(MissionType.Mission, MissionCodec.expand(waypoints))) {
            is MissionResult.Success -> buildJsonObject { put("ok", true) }
            is MissionResult.Failure -> failure("the upload failed: ${r.error}")
        }
    }

    private fun refusal(s: VehicleServices): String? = when (s.vehicle.telemetry.heartbeat.value?.armed) {
        true -> "cannot write mission while armed"
        null -> "cannot write mission while the arm state is unknown"
        false -> null
    }

    private companion object {
        const val SERVICES_WAIT_MS = 2_000L
    }
}
