package com.altnautica.gcs.core.agent.sensors

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Requested RTK base mode for `PUT /api/v1/gnss/base`. */
sealed interface BaseModeRequest {
    data object Off : BaseModeRequest
    data class Survey(val minDurS: Long, val accuracyM: Double) : BaseModeRequest
    data class Fixed(val latDeg: Double, val lonDeg: Double, val hEllipsoidM: Double) : BaseModeRequest
}

/**
 * Sensor, clock, storage, GNSS and LoRa routes of one node. An interface so
 * screens can be driven by the agent or by demo data alike.
 */
interface SensorsApi {
    suspend fun clock(): AgentResult<ClockStatus>
    suspend fun sensors(): AgentResult<SensorsStatus>
    suspend fun storage(): AgentResult<StorageStatus>
    suspend fun gnss(): AgentResult<GnssStatus>
    suspend fun putGnssBase(request: BaseModeRequest): AgentResult<GnssBase>
    suspend fun lora(): AgentResult<LoraStatus>
    suspend fun loraRegions(): AgentResult<LoraRegions>
    suspend fun loraBeacons(): AgentResult<LoraBeacons>

    /** Sets the LoRa region; null follows the node's country setting. Admin scope; the agent refuses while armed. */
    suspend fun putLoraRegion(code: String?): AgentResult<LoraStatus>
}

/** [SensorsApi] over the agent's `/api/v1` routes. */
class AgentSensorsApi internal constructor(private val client: AgentClient) : SensorsApi {

    override suspend fun clock() = client.get("api/v1/clock", ClockStatus.serializer())

    override suspend fun sensors() = client.get("api/v1/sensors", SensorsStatus.serializer())

    override suspend fun storage() = client.get("api/v1/storage", StorageStatus.serializer())

    override suspend fun gnss() = client.get("api/v1/gnss", GnssStatus.serializer())

    override suspend fun putGnssBase(request: BaseModeRequest) =
        client.sendJson("PUT", "api/v1/gnss/base", baseBody(request), GnssBase.serializer())

    override suspend fun lora() = client.get("api/v1/lora", LoraStatus.serializer())

    override suspend fun loraRegions() = client.get("api/v1/lora/regions", LoraRegions.serializer())

    override suspend fun loraBeacons() = client.get("api/v1/lora/beacons", LoraBeacons.serializer())

    override suspend fun putLoraRegion(code: String?) =
        client.sendJson("PUT", "api/v1/lora", regionBody(code), LoraStatus.serializer())

    internal companion object {
        fun baseBody(request: BaseModeRequest): JsonObject = buildJsonObject {
            when (request) {
                BaseModeRequest.Off -> put("mode", "off")
                is BaseModeRequest.Survey -> {
                    put("mode", "survey")
                    put("min_dur_s", request.minDurS)
                    put("accuracy_m", request.accuracyM)
                }
                is BaseModeRequest.Fixed -> {
                    put("mode", "fixed")
                    put("lat_deg", request.latDeg)
                    put("lon_deg", request.lonDeg)
                    put("h_ellipsoid_m", request.hEllipsoidM)
                }
            }
        }

        fun regionBody(code: String?): JsonObject = buildJsonObject {
            if (code == null) put("region", JsonNull) else put("region", code)
        }
    }
}
