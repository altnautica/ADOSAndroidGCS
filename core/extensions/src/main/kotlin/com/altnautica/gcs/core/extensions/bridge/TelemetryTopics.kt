package com.altnautica.gcs.core.extensions.bridge

import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.VehicleTelemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Telemetry topics an extension can subscribe to, each mapped from one per-topic flow of
 * the vehicle. Payloads use the field names and units extensions are written against
 * (degrees for angles, metres, m/s, epoch-free `timestamp` in ms of the link clock).
 */
object TelemetryTopics {

    private fun JsonObjectBuilder.putOrNull(key: String, v: Number?) =
        put(key, if (v == null) JsonNull else JsonPrimitive(v))

    private fun deg(rad: Float): Double = Math.toDegrees(rad.toDouble())

    private val BUILDERS: Map<String, (VehicleTelemetry) -> Flow<JsonElement>> = run {
        val attitude: (VehicleTelemetry) -> Flow<JsonElement> = { t ->
            t.attitude.filterNotNull().map { a ->
                buildJsonObject {
                    put("timestamp", a.tMs)
                    put("roll", deg(a.rollRad))
                    put("pitch", deg(a.pitchRad))
                    put("yaw", deg(a.yawRad))
                }
            }
        }
        val position: (VehicleTelemetry) -> Flow<JsonElement> = { t ->
            t.position.filterNotNull().map { p ->
                buildJsonObject {
                    put("timestamp", p.tMs)
                    put("lat", p.latDeg)
                    put("lon", p.lonDeg)
                    put("alt", p.altMslM)
                    put("relativeAlt", p.altRelM)
                    put("groundSpeed", Math.hypot(p.vxMs.toDouble(), p.vyMs.toDouble()))
                    put("climbRate", -p.vzMs)
                    put("vn", p.vxMs)
                    put("ve", p.vyMs)
                    put("vd", p.vzMs)
                }
            }
        }
        val battery: (VehicleTelemetry) -> Flow<JsonElement> = { t ->
            t.battery.filterNotNull().map { b ->
                buildJsonObject {
                    put("timestampMs", b.tMs)
                    put("packId", b.id)
                    putOrNull("totalVoltageV", b.voltageV)
                    putOrNull("currentA", b.currentA)
                    putOrNull("remainingPercent", b.remainingPct)
                    putOrNull("timeToReserveS", b.timeToReserveS)
                }
            }
        }
        val gps: (VehicleTelemetry) -> Flow<JsonElement> = { t ->
            t.gps.filterNotNull().map { g ->
                buildJsonObject {
                    put("timestamp", g.tMs)
                    put("fixType", fixType(g.fix))
                    putOrNull("satellites", g.satellites)
                    putOrNull("hdop", g.hdop)
                }
            }
        }
        val vfr: (VehicleTelemetry) -> Flow<JsonElement> = { t ->
            t.vfr.filterNotNull().map { v ->
                buildJsonObject {
                    put("timestamp", v.tMs)
                    put("airspeed", v.airspeedMs)
                    put("groundspeed", v.groundspeedMs)
                    put("heading", v.headingDeg)
                    put("throttle", v.throttlePct)
                    put("climb", v.climbMs)
                }
            }
        }
        val heartbeat: (VehicleTelemetry) -> Flow<JsonElement> = { t ->
            t.heartbeat.filterNotNull().map { h ->
                buildJsonObject {
                    put("timestamp", h.tMs)
                    put("armed", h.armed)
                    put("mode", h.mode.name)
                    put("customMode", h.mode.customMode)
                    put("vehicleType", h.vehicleType)
                    put("autopilot", h.autopilot)
                }
            }
        }
        val sysStatus: (VehicleTelemetry) -> Flow<JsonElement> = { t ->
            t.systemHealth.filterNotNull().map { s ->
                buildJsonObject {
                    put("timestamp", s.tMs)
                    put("systemStatus", s.systemStatus)
                    put("sensorsPresent", s.sensorsPresent)
                    put("sensorsEnabled", s.sensorsEnabled)
                    put("sensorsHealthy", s.sensorsHealthy)
                }
            }
        }
        val statusText: (VehicleTelemetry) -> Flow<JsonElement> = { t ->
            t.statusText.map { s ->
                buildJsonObject {
                    put("timestamp", s.tMs)
                    put("severity", s.severity)
                    put("text", s.text)
                }
            }
        }
        mapOf(
            "mavlink.attitude" to attitude, "attitude" to attitude,
            "mavlink.position" to position, "position" to position,
            "mavlink.battery" to battery, "battery" to battery,
            "mavlink.gps" to gps, "gps" to gps,
            "mavlink.vfr" to vfr, "vfr" to vfr,
            "mavlink.HEARTBEAT" to heartbeat, "heartbeat" to heartbeat,
            "mavlink.SYS_STATUS" to sysStatus, "sysStatus" to sysStatus,
            "mavlink.STATUSTEXT" to statusText, "statustext" to statusText,
        )
    }

    /** Whether [topic] is fed from vehicle telemetry (others are the extension's own agent channels). */
    fun isVehicleTopic(topic: String): Boolean = topic in BUILDERS

    fun flow(topic: String, telemetry: VehicleTelemetry): Flow<JsonElement>? = BUILDERS[topic]?.invoke(telemetry)

    private fun fixType(fix: GpsFix): Int = when (fix) {
        GpsFix.NoGps -> 0
        GpsFix.NoFix -> 1
        GpsFix.Fix2D -> 2
        GpsFix.Fix3D -> 3
        GpsFix.DGps -> 4
        GpsFix.RtkFloat -> 5
        GpsFix.RtkFixed -> 6
    }
}
