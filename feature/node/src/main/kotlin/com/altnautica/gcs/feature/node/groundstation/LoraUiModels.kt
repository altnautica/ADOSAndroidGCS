package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.core.agent.sensors.CrsfModule
import com.altnautica.gcs.core.agent.sensors.LoraBeacon
import com.altnautica.gcs.core.agent.sensors.LoraRegionPlan

data class ChannelUi(val freqMhz: Double?, val bwKhz: Int?, val sf: Int?, val erpMw: Double?, val maxErpMw: Double?)

data class MeshtasticUi(val connected: Boolean, val port: String?, val regionPreset: String?)

data class BeaconUi(
    val nodeId: Long,
    val latDeg: Double?,
    val lonDeg: Double?,
    val altM: Double?,
    val batteryPct: Int?,
    val mode: String?,
    val rssiDbm: Double?,
    val snrDb: Double?,
    val receivedAtMs: Long?,
)

/** One row of the region picker. */
data class RegionOptionUi(
    val code: String,
    val name: String?,
    val planId: String?,
    val bandMhz: List<Double>,
    /** Rule kinds the plan declares; empty = no verified plan (receive only). */
    val rules: List<String>,
    /** Null = the module's range is unknown. */
    val moduleOk: Boolean?,
)

/** Amber advisory chips under the region line; they never block (the agent does that). */
sealed interface LoraMismatch {
    /** An ELRS module reports [domain] where the region expects [expected] on its band. */
    data class ElrsDomain(val moduleId: String, val domain: String, val expected: String) : LoraMismatch

    /** The Meshtastic bridge runs preset [preset] while the region's table maps to [expected]. */
    data class MeshtasticPreset(val preset: String, val expected: String) : LoraMismatch
}

object LoraLinks {

    /** ELRS 2.4 GHz domains; every other reported domain is a sub-GHz one (`900` key). */
    private val DOMAINS_2G4 = setOf("ISM2G4", "CE_LBT")

    fun beacon(b: LoraBeacon) = BeaconUi(b.nodeId, b.latDeg, b.lonDeg, b.altM, b.batteryPct, b.mode, b.rssiDbm, b.snrDb, b.receivedAtMs)

    fun option(p: LoraRegionPlan) = RegionOptionUi(p.code, p.name, p.planId, p.bandMhz, p.rules, p.supportedByModule)

    /** The band key a reported ELRS domain belongs to in `elrs_domains`. */
    fun elrsBand(domain: String): String = if (domain in DOMAINS_2G4) "2g4" else "900"

    /**
     * Advisory mismatches for the active [regionCode]. A module whose domain is
     * unknown, or a region whose expected domain for that band is unverified
     * (null), raises nothing: there is no verified expectation to differ from.
     * The Meshtastic preset is compared by exact equality with the region's
     * [expectedPreset], only while the bridge is enabled ([meshtastic] is
     * non-null only then) and both values are known.
     */
    fun mismatches(
        regionCode: String?,
        plans: List<LoraRegionPlan>,
        modules: List<CrsfModule>,
        meshtastic: MeshtasticUi?,
        expectedPreset: String?,
    ): List<LoraMismatch> {
        if (regionCode == null) return emptyList()
        val plan = plans.firstOrNull { it.code == regionCode }
        val elrs = modules.mapNotNull { m ->
            val domain = m.domain ?: return@mapNotNull null
            val expected = plan?.elrsDomains?.get(elrsBand(domain)) ?: return@mapNotNull null
            if (domain != expected) LoraMismatch.ElrsDomain(m.id, domain, expected) else null
        }
        val preset = meshtastic?.regionPreset
        val mesh = if (preset != null && expectedPreset != null && preset != expectedPreset) {
            listOf(LoraMismatch.MeshtasticPreset(preset, expectedPreset))
        } else {
            emptyList()
        }
        return elrs + mesh
    }
}
