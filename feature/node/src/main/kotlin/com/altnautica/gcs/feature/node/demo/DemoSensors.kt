package com.altnautica.gcs.feature.node.demo

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.sensors.BaseModeRequest
import com.altnautica.gcs.core.agent.sensors.CaptureStorage
import com.altnautica.gcs.core.agent.sensors.ClockSource
import com.altnautica.gcs.core.agent.sensors.ClockStatus
import com.altnautica.gcs.core.agent.sensors.GnssBase
import com.altnautica.gcs.core.agent.sensors.GnssHeading
import com.altnautica.gcs.core.agent.sensors.GnssPosition
import com.altnautica.gcs.core.agent.sensors.GnssReceiver
import com.altnautica.gcs.core.agent.sensors.GnssStatus
import com.altnautica.gcs.core.agent.sensors.LoraBeacon
import com.altnautica.gcs.core.agent.sensors.LoraBeacons
import com.altnautica.gcs.core.agent.sensors.LoraChannel
import com.altnautica.gcs.core.agent.sensors.LoraGovernor
import com.altnautica.gcs.core.agent.sensors.LoraMeshtastic
import com.altnautica.gcs.core.agent.sensors.LoraRegion
import com.altnautica.gcs.core.agent.sensors.LoraRegionPlan
import com.altnautica.gcs.core.agent.sensors.LoraRegions
import com.altnautica.gcs.core.agent.sensors.LoraRule
import com.altnautica.gcs.core.agent.sensors.LoraRx
import com.altnautica.gcs.core.agent.sensors.LoraStatus
import com.altnautica.gcs.core.agent.sensors.LoraTx
import com.altnautica.gcs.core.agent.sensors.RigSensor
import com.altnautica.gcs.core.agent.sensors.RtcmOut
import com.altnautica.gcs.core.agent.sensors.SensorFc
import com.altnautica.gcs.core.agent.sensors.SensorHub
import com.altnautica.gcs.core.agent.sensors.SensorsApi
import com.altnautica.gcs.core.agent.sensors.SensorsStatus
import com.altnautica.gcs.core.agent.sensors.StorageStatus
import com.altnautica.gcs.core.agent.sensors.StorageVolume

// Demo-mode seeds. Shown only for the demo nodes; never mixed into a real node.

/** Base for a demo node: services it does not carry answer `present: false`, like a real agent. */
open class DemoSensorsBase : SensorsApi {
    override suspend fun clock(): AgentResult<ClockStatus> = AgentResult.Ok(ClockStatus(present = false))
    override suspend fun sensors(): AgentResult<SensorsStatus> = AgentResult.Ok(SensorsStatus(present = false))
    override suspend fun storage(): AgentResult<StorageStatus> = AgentResult.Ok(StorageStatus(present = false))
    override suspend fun gnss(): AgentResult<GnssStatus> = AgentResult.Ok(GnssStatus(present = false))
    override suspend fun putGnssBase(request: BaseModeRequest): AgentResult<GnssBase> = AgentResult.Err(AgentError.FeatureAbsent)
    override suspend fun lora(): AgentResult<LoraStatus> = AgentResult.Ok(LoraStatus(present = false))
    override suspend fun loraRegions(): AgentResult<LoraRegions> = AgentResult.Ok(LoraRegions())
    override suspend fun loraBeacons(): AgentResult<LoraBeacons> = AgentResult.Ok(LoraBeacons())
    override suspend fun putLoraRegion(code: String?): AgentResult<LoraStatus> = AgentResult.Err(AgentError.FeatureAbsent)
}

/** Demo Visor: hub IMU 1000 Hz, two cameras at 20 Hz, clock sigma 0.42 ms, NVMe capture volume. */
class DemoVisorSensors : DemoSensorsBase() {
    override suspend fun clock() = AgentResult.Ok(
        ClockStatus(
            present = true, timebase = "hub_freerun", locked = true, sigmaNs = 420_000, tNs = null, utcNs = null,
            sources = listOf(
                ClockSource("hub", "hub", true, 12_000, -3.2, 420_000, null),
                ClockSource("fc", "fc", true, -37_200_000, 40.1, 800_000, null),
                ClockSource("gnss", "gnss_pps", false, null, null, -1, null),
            ),
        ),
    )

    override suspend fun sensors() = AgentResult.Ok(
        SensorsStatus(
            present = true, rigId = "visor-pro-01", calibVersion = "demo-a",
            hub = SensorHub(true, "serial:/dev/ttyACM0", "ados-sensorhub 0.1.0", "matek_h743_slim_v3", 1, 321, 0, 0),
            fc = SensorFc(true, "ardupilot", true),
            sensors = listOf(
                RigSensor("imu0", "imu", "nav", "hub", 1000.0, 1000.0, null, 1.0, 420_000),
                RigSensor("cam_fl", "camera", "front_left", "vision", 20.0, 20.0, null, 9.0, 420_000),
                RigSensor("cam_fr", "camera", "front_right", "vision", 19.8, 20.0, null, 12.0, 420_000),
            ),
            gnss = null,
        ),
    )

    override suspend fun storage() = AgentResult.Ok(
        StorageStatus(
            present = true, captureRoot = "/mnt/nvme0/ados/capture",
            volumes = listOf(StorageVolume("/mnt/nvme0", "nvme0n1", "ext4", 1_000_204_886_016, 812_345_678_901, false, true)),
            capture = CaptureStorage(123_456_789_012, 850_174_153_113, 85, 5_368_709_120, 2, 1, false, "ok"),
        ),
    )
}

/** Demo ground station: RTK base surveying toward fixed, and a LoRa lane at 0.4 % duty in the India plan. */
class DemoGroundSensors(private val now: () -> Long = System::currentTimeMillis) : DemoSensorsBase() {
    private val openedMs = now()
    private var base = GnssBase("survey", "surveying", 0, 300, 3.1, 2.0, null, RtcmOut(0.0, 0, 0))
    private var baseStartMs = openedMs
    private var regionCode: String? = "IN"

    override suspend fun gnss(): AgentResult<GnssStatus> {
        val elapsed = (now() - baseStartMs) / 1000
        val b = when (base.mode) {
            "survey" -> if (elapsed >= (base.minDurS ?: 300)) {
                base.copy(state = "fixed", elapsedS = elapsed, accuracyM = 1.4, fixed = POSITION, rtcmOut = RtcmOut(6.0, elapsed * 900, 2))
            } else {
                base.copy(elapsedS = elapsed, accuracyM = (3.1 - elapsed * 0.006).coerceAtLeast(1.4))
            }
            else -> base
        }
        return AgentResult.Ok(
            GnssStatus(
                present = true,
                receivers = listOf(
                    GnssReceiver("gnss0", "serial", "u-blox M10", "3d", 14, 0.8, 12.9716, 77.5946, 920.4, 1006.1, "none", true),
                ),
                base = b,
                heading = GnssHeading(available = false),
            ),
        )
    }

    override suspend fun putGnssBase(request: BaseModeRequest): AgentResult<GnssBase> {
        baseStartMs = now()
        base = when (request) {
            BaseModeRequest.Off -> GnssBase("off", "off", null, null, null, null, null, RtcmOut(0.0, 0, 0))
            is BaseModeRequest.Survey -> GnssBase("survey", "surveying", 0, request.minDurS, 3.1, request.accuracyM, null, RtcmOut(0.0, 0, 0))
            is BaseModeRequest.Fixed ->
                GnssBase("fixed", "fixed", null, null, null, null, GnssPosition(request.latDeg, request.lonDeg, request.hEllipsoidM), RtcmOut(6.0, 0, 2))
        }
        return AgentResult.Ok(base)
    }

    override suspend fun lora(): AgentResult<LoraStatus> {
        val code = regionCode
        if (code != "IN") return AgentResult.Ok(regionUnsetStatus())
        return AgentResult.Ok(
            LoraStatus(
                present = true, transport = "e22", moduleBand = "850-930",
                region = LoraRegion("IN", "India", "IN865", "network.regulatory.region", listOf(865.0, 867.0), "G.S.R. 853(E), 2021"),
                channel = LoraChannel(866.0, 125, 9, 25.0, 25.0),
                nodeId = 17, txAllowed = true, blockedReason = null,
                governor = LoraGovernor(
                    rules = listOf(
                        LoraRule("duty_cycle", windowS = 3600, limitPct = 1.0, usedPct = 0.4, airtimeMsWindow = 14_400, budgetMsWindow = 36_000, exhausted = false, subBand = "865.0-867.0"),
                        LoraRule("max_power", limitMw = 25.0, configuredMw = 25.0, ok = true),
                    ),
                    nextTxAllowedMs = 0,
                    lastRefusal = null,
                ),
                tx = LoraTx(120, 0, mapOf("duty_cycle" to 0L, "max_power" to 0L, "region_unset" to 0L)),
                rx = LoraRx(340, 2),
                meshtastic = LoraMeshtastic(false, false, null, "IN"),
            ),
        )
    }

    override suspend fun loraRegions() = AgentResult.Ok(
        LoraRegions(
            listOf(
                LoraRegionPlan("IN", "India", "IN865", listOf(865.0, 867.0), listOf("duty_cycle", "max_power"), true),
                LoraRegionPlan("EU", "European Union (ETSI)", "EU868", listOf(863.0, 870.0), listOf("duty_cycle", "max_power"), true),
                LoraRegionPlan("US", "United States", "US915", listOf(902.0, 928.0), listOf("dwell_time", "max_power"), true),
                LoraRegionPlan("JP", "Japan", "AS923-1-JP", listOf(920.5, 923.5), listOf("lbt", "dwell_time", "max_power"), false),
            ),
        ),
    )

    override suspend fun loraBeacons(): AgentResult<LoraBeacons> {
        val t = now()
        return AgentResult.Ok(
            LoraBeacons(
                listOf(
                    LoraBeacon(42, "beacon", 12.9718, 77.5951, 42.0, 78, "AUTO", -92.0, 7.5, t - 12_000),
                    LoraBeacon(43, "beacon", 12.9702, 77.5938, 38.0, 64, "LOITER", -101.0, 2.0, t - 41_000),
                ),
            ),
        )
    }

    /** Demo of the picker: India keeps the seeded plan; any other choice shows the receive-only state. */
    override suspend fun putLoraRegion(code: String?): AgentResult<LoraStatus> {
        regionCode = code ?: "IN"
        return lora()
    }

    private fun regionUnsetStatus() = LoraStatus(
        present = true, transport = "e22", moduleBand = "850-930", region = null, channel = null, nodeId = 17,
        txAllowed = false, blockedReason = "region_unset",
        governor = LoraGovernor(rules = emptyList(), nextTxAllowedMs = null, lastRefusal = null),
        tx = LoraTx(120, 0, mapOf("region_unset" to 0L)), rx = LoraRx(340, 2),
        meshtastic = LoraMeshtastic(false, false, null, null),
    )

    private companion object {
        val POSITION = GnssPosition(12.9716, 77.5946, 920.4)
    }
}
