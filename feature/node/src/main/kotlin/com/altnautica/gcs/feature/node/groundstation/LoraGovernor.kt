package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.core.agent.sensors.LoraRule
import com.altnautica.gcs.core.agent.sensors.LoraStatus

/** Meter colour band: below 70 % of the limit, 70-99 %, at the limit. */
enum class MeterLevel { Normal, Near, AtLimit }

/** One governor rule the active band plan declares, ready to draw as a meter row. */
sealed interface RuleMeter {
    val level: MeterLevel

    data class Duty(
        val subBand: String?,
        val airtimeMs: Long?,
        val budgetMs: Long?,
        val usedPct: Double?,
        val limitPct: Double?,
        val windowS: Long?,
        val fraction: Float?,
        override val level: MeterLevel,
    ) : RuleMeter

    data class Dwell(
        val lastTxMs: Long?,
        val limitMs: Long?,
        val perS: Long?,
        val fraction: Float?,
        override val level: MeterLevel,
    ) : RuleMeter

    data class Lbt(
        val thresholdDbm: Double?,
        val listenUs: Long?,
        val lastRssiDbm: Double?,
        /** The last sample is at or above the threshold; null when either is unknown. */
        val busy: Boolean?,
        val busyCount: Long?,
    ) : RuleMeter {
        override val level: MeterLevel get() = if (busy == true) MeterLevel.Near else MeterLevel.Normal
    }

    data class Power(
        val configuredMw: Double?,
        val limitMw: Double?,
        val fraction: Float?,
        override val level: MeterLevel,
    ) : RuleMeter
}

/** Why transmit is blocked, from `blocked_reason`. */
enum class LoraBlockReason { RegionUnset, ModuleBandMismatch, DutyExhausted, DwellTime, LbtBusy, PowerOverLimit, Other }

/** What the banner offers to fix the block. */
enum class LoraFix { SetRegion, ChangeRegion, PowerSettings, None }

data class LoraBlock(
    val reason: LoraBlockReason,
    val fix: LoraFix,
    /** Blocks that clear by themselves show the next-TX countdown. */
    val countdown: Boolean,
    /** A temporary pause (duty/dwell/lbt) rather than a configuration block. */
    val paused: Boolean,
    val raw: String,
)

data class RefusalCount(val rule: String, val count: Long)

/** The LoRa tab's view of `GET /api/v1/lora`. */
data class LoraUi(
    val regionCode: String?,
    val regionName: String?,
    val planId: String?,
    /** True when the region follows the node's country setting rather than `lora.region`. */
    val regionFromCountry: Boolean,
    val bandMhz: List<Double>,
    val citation: String?,
    /** Meshtastic preset the active region expects; null = none. */
    val expectedMeshtasticPreset: String?,
    val transport: String?,
    val moduleBand: String?,
    val channel: ChannelUi?,
    val nodeId: Int?,
    val txAllowed: Boolean,
    val block: LoraBlock?,
    val meters: List<RuleMeter>,
    val nextTxAllowedMs: Long?,
    val refusals: List<RefusalCount>,
    val refusedTotal: Long?,
    val lastRefusalRule: String?,
    val lastRefusalAtMs: Long?,
    val txFrames: Long?,
    val rxFrames: Long?,
    val crcErrors: Long?,
    /** Present only while the Meshtastic bridge is enabled. */
    val meshtastic: MeshtasticUi?,
) {
    /**
     * The next-TX time means something only while transmit is allowed or the
     * block clears on its own (duty, dwell); a configuration block never shows
     * "now".
     */
    val showNextTx: Boolean get() = nextTxAllowedMs != null && (txAllowed || block?.countdown == true)
}

object LoraGovernor {

    /** Rule kinds in the order refusal counts are listed. */
    val REFUSAL_ORDER = listOf("duty_cycle", "dwell_time", "lbt", "max_power", "region_unset")

    fun map(s: LoraStatus): LoraUi {
        val governor = s.governor
        val refused = s.tx?.refusedByRule.orEmpty()
        val ordered = REFUSAL_ORDER.filter { it in refused } + refused.keys.filterNot { it in REFUSAL_ORDER }.sorted()
        return LoraUi(
            regionCode = s.region?.code,
            regionName = s.region?.name,
            planId = s.region?.planId,
            regionFromCountry = s.region?.source == "network.regulatory.region",
            bandMhz = s.region?.bandMhz.orEmpty(),
            citation = s.region?.citation,
            expectedMeshtasticPreset = s.region?.meshtasticPreset,
            transport = s.transport,
            moduleBand = s.moduleBand,
            channel = s.channel?.let { ChannelUi(it.freqMhz, it.bwKhz, it.sf, it.erpMw, it.maxErpMw) },
            nodeId = s.nodeId,
            txAllowed = s.txAllowed,
            block = block(s.blockedReason, s.txAllowed),
            meters = governor?.rules.orEmpty().mapNotNull(::meter),
            nextTxAllowedMs = governor?.nextTxAllowedMs,
            refusals = ordered.map { RefusalCount(it, refused.getValue(it)) },
            refusedTotal = s.tx?.refused,
            lastRefusalRule = governor?.lastRefusal?.rule,
            lastRefusalAtMs = governor?.lastRefusal?.atMs,
            txFrames = s.tx?.frames,
            rxFrames = s.rx?.frames,
            crcErrors = s.rx?.crcErrors,
            meshtastic = s.meshtastic?.takeIf { it.enabled }?.let { MeshtasticUi(it.connected, it.port, it.regionPreset) },
        )
    }

    /**
     * The banner for [reason]. A node that refuses transmit without naming a
     * reason still gets a banner: transmit is never shown as allowed when the
     * agent says it is not.
     */
    fun block(reason: String?, txAllowed: Boolean): LoraBlock? {
        if (reason == null) return if (txAllowed) null else LoraBlock(LoraBlockReason.Other, LoraFix.None, false, false, "")
        val kind = when (reason) {
            "region_unset" -> LoraBlockReason.RegionUnset
            "module_band_mismatch" -> LoraBlockReason.ModuleBandMismatch
            "duty_exhausted" -> LoraBlockReason.DutyExhausted
            "dwell_time" -> LoraBlockReason.DwellTime
            "lbt_busy" -> LoraBlockReason.LbtBusy
            "power_over_limit" -> LoraBlockReason.PowerOverLimit
            else -> LoraBlockReason.Other
        }
        val fix = when (kind) {
            LoraBlockReason.RegionUnset -> LoraFix.SetRegion
            LoraBlockReason.ModuleBandMismatch -> LoraFix.ChangeRegion
            LoraBlockReason.PowerOverLimit -> LoraFix.PowerSettings
            else -> LoraFix.None
        }
        val countdown = kind == LoraBlockReason.DutyExhausted || kind == LoraBlockReason.DwellTime
        val paused = countdown || kind == LoraBlockReason.LbtBusy
        return LoraBlock(kind, fix, countdown, paused, reason)
    }

    fun meter(rule: LoraRule): RuleMeter? = when (rule.kind) {
        "duty_cycle" -> {
            val fraction = ratio(rule.airtimeMsWindow?.toDouble(), rule.budgetMsWindow?.toDouble())
                ?: ratio(rule.usedPct, rule.limitPct)
            RuleMeter.Duty(
                subBand = rule.subBand,
                airtimeMs = rule.airtimeMsWindow,
                budgetMs = rule.budgetMsWindow,
                usedPct = rule.usedPct,
                limitPct = rule.limitPct,
                windowS = rule.windowS,
                fraction = fraction,
                level = level(fraction, atLimit = rule.exhausted == true),
            )
        }
        "dwell_time" -> {
            val fraction = ratio(rule.lastTxMs?.toDouble(), rule.limitMs?.toDouble())
            RuleMeter.Dwell(rule.lastTxMs, rule.limitMs, rule.perS, fraction, level(fraction, atLimit = rule.ok == false))
        }
        "lbt" -> {
            val rssi = rule.lastRssiDbm
            val threshold = rule.thresholdDbm
            RuleMeter.Lbt(threshold, rule.listenUs, rssi, if (rssi != null && threshold != null) rssi >= threshold else null, rule.busyCount)
        }
        "max_power" -> {
            // Power configured at the plan's clamp is the expected state; only above it is a limit hit.
            val fraction = ratio(rule.configuredMw, rule.limitMw)
            val over = rule.ok == false || (fraction != null && fraction > 1f)
            RuleMeter.Power(rule.configuredMw, rule.limitMw, fraction, if (over) MeterLevel.AtLimit else MeterLevel.Normal)
        }
        else -> null
    }

    /** Consumable budgets (duty, dwell): at or past the limit, or flagged by the agent, is at limit. */
    fun level(fraction: Float?, atLimit: Boolean): MeterLevel = when {
        atLimit -> MeterLevel.AtLimit
        fraction == null -> MeterLevel.Normal
        fraction >= 1f -> MeterLevel.AtLimit
        fraction >= NEAR -> MeterLevel.Near
        else -> MeterLevel.Normal
    }

    private fun ratio(used: Double?, limit: Double?): Float? =
        if (used == null || limit == null || limit <= 0.0) null else (used / limit).toFloat()

    private const val NEAR = 0.7f
}
