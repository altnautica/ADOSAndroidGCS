package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.EmptyState
import com.altnautica.gcs.core.designsystem.component.StatusChip
import com.altnautica.gcs.core.designsystem.component.StatusLevel
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.DASH
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.fmt
import com.altnautica.gcs.feature.node.common.fmtInt
import kotlinx.coroutines.delay

/** LoRa tab body: region, blocked banner, one meter per governor rule, refusals, beacons, Meshtastic. */
@Composable
internal fun LoraView(lanes: LanesUiState, vm: GroundStationViewModel) {
    val lora = (lanes.lora as? Load.Ready)?.value
    if (lora == null) {
        EmptyState(
            icon = Icons.Outlined.SettingsInputAntenna,
            title = stringResource(R.string.gs_lora_absent_title),
            message = stringResource(R.string.gs_lora_absent_message),
        )
        return
    }
    var picking by rememberSaveable { mutableStateOf(false) }
    val remainingMs by produceState(remaining(lora.nextTxAllowedMs, lanes.loraReadAtMs), lora.nextTxAllowedMs, lanes.loraReadAtMs) {
        while (true) {
            value = remaining(lora.nextTxAllowedMs, lanes.loraReadAtMs)
            delay(TICK_MS)
        }
    }
    lora.block?.let { BlockedBanner(it, lora, remainingMs) { picking = true } }
    RegionCard(lora, lanes.mismatches, remainingMs, lanes.armed) { picking = true }
    GovernorCard(lora)
    RefusalsCard(lora)
    BeaconsCard(lanes.beacons)
    lora.meshtastic?.let { m ->
        NodeCard(stringResource(R.string.gs_lora_meshtastic)) {
            StatusChip(
                if (m.connected) StatusLevel.Success else StatusLevel.Warning,
                stringResource(if (m.connected) R.string.gs_lora_connected else R.string.gs_lora_disconnected),
            )
            KeyValueRow(stringResource(R.string.gs_lora_port), m.port)
            KeyValueRow(stringResource(R.string.gs_lora_preset), m.regionPreset)
        }
    }
    if (picking) {
        LoraRegionPicker(lanes, current = lora.regionCode, followsCountry = lora.regionFromCountry, vm = vm, onDismiss = { picking = false })
    }
}

@Composable
private fun RegionCard(lora: LoraUi, mismatches: List<LoraMismatch>, remainingMs: Long, armed: Boolean, onChange: () -> Unit) {
    NodeCard(stringResource(R.string.gs_lora_region)) {
        val region = lora.regionCode?.let { code -> listOfNotNull(code, lora.regionName, lora.planId).joinToString(" · ") }
        StatusChip(
            if (region != null) StatusLevel.Info else StatusLevel.Warning,
            region ?: stringResource(R.string.gs_lora_region_none),
        )
        KeyValueRow(
            stringResource(R.string.gs_lora_region_source),
            lora.regionCode?.let { stringResource(if (lora.regionFromCountry) R.string.gs_lora_source_country else R.string.gs_lora_source_lora) },
        )
        KeyValueRow(stringResource(R.string.gs_lora_band), band(lora.bandMhz))
        mismatches.forEach { StatusChip(StatusLevel.Warning, mismatchText(it)) }
        KeyValueRow(stringResource(R.string.gs_lora_citation), lora.citation)
        KeyValueRow(stringResource(R.string.gs_lora_module), listOfNotNull(lora.transport, lora.moduleBand?.let { "$it MHz" }).joinToString(" · ").ifEmpty { null })
        lora.channel?.let { c ->
            KeyValueRow(stringResource(R.string.gs_lora_channel), listOfNotNull(c.freqMhz.fmt(3, " MHz"), c.bwKhz.fmtInt(" kHz"), c.sf?.let { "SF$it" }).joinToString(" · "))
            KeyValueRow(stringResource(R.string.gs_lora_power), listOfNotNull(c.erpMw.fmt(0), c.maxErpMw.fmt(0, " mW ERP")).joinToString(" / ").ifEmpty { null })
        }
        StatusChip(
            if (lora.txAllowed) StatusLevel.Success else StatusLevel.Error,
            stringResource(if (lora.txAllowed) R.string.gs_lora_tx_allowed else R.string.gs_lora_tx_blocked),
        )
        if (lora.showNextTx) {
            KeyValueRow(
                stringResource(R.string.gs_lora_next_tx),
                if (remainingMs <= 0) stringResource(R.string.gs_lora_now) else countdown(remainingMs),
            )
        }
        AdosButton(
            label = stringResource(R.string.gs_lora_change_region),
            onClick = onChange,
            enabled = !armed,
            variant = AdosButtonVariant.Secondary,
            modifier = Modifier.fillMaxWidth(),
        )
        if (armed) Text(stringResource(R.string.gs_lora_region_locked_armed), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
    }
}

@Composable
private fun mismatchText(m: LoraMismatch): String = when (m) {
    is LoraMismatch.ElrsDomain -> stringResource(R.string.gs_lora_mismatch_elrs, m.moduleId, m.domain, m.expected)
    is LoraMismatch.MeshtasticPreset -> stringResource(R.string.gs_lora_mismatch_meshtastic, m.preset, m.region)
}

@Composable
private fun BlockedBanner(block: LoraBlock, lora: LoraUi, remainingMs: Long, onChangeRegion: () -> Unit) {
    NodeCard(null) {
        StatusChip(
            if (block.paused) StatusLevel.Warning else StatusLevel.Error,
            stringResource(if (block.paused) R.string.gs_lora_tx_paused else R.string.gs_lora_tx_blocked),
            icon = if (block.paused) Icons.Outlined.PauseCircle else Icons.Outlined.Block,
        )
        Text(blockText(block, lora), style = MaterialTheme.typography.bodyMedium)
        if (block.countdown && lora.nextTxAllowedMs != null) {
            Text(
                if (remainingMs > 0) stringResource(R.string.gs_lora_next_tx_in, countdown(remainingMs)) else stringResource(R.string.gs_lora_next_tx_now),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (block.fix) {
            LoraFix.SetRegion, LoraFix.ChangeRegion -> AdosButton(
                label = stringResource(if (block.fix == LoraFix.SetRegion) R.string.gs_lora_set_region else R.string.gs_lora_change_region),
                onClick = onChangeRegion,
                modifier = Modifier.fillMaxWidth(),
            )
            LoraFix.PowerSettings -> Text(stringResource(R.string.gs_lora_fix_power), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
            LoraFix.None -> Unit
        }
    }
}

@Composable
private fun blockText(block: LoraBlock, lora: LoraUi): String = when (block.reason) {
    LoraBlockReason.RegionUnset -> stringResource(R.string.gs_lora_block_region_unset)
    LoraBlockReason.ModuleBandMismatch -> stringResource(R.string.gs_lora_block_band, lora.moduleBand ?: DASH, band(lora.bandMhz) ?: DASH)
    LoraBlockReason.DutyExhausted -> stringResource(R.string.gs_lora_block_duty)
    LoraBlockReason.DwellTime -> stringResource(R.string.gs_lora_block_dwell)
    LoraBlockReason.LbtBusy -> stringResource(R.string.gs_lora_block_lbt)
    LoraBlockReason.PowerOverLimit -> stringResource(R.string.gs_lora_block_power)
    LoraBlockReason.Other -> if (block.raw.isEmpty()) stringResource(R.string.gs_lora_block_unknown) else stringResource(R.string.gs_lora_block_other, block.raw)
}

@Composable
private fun GovernorCard(lora: LoraUi) {
    NodeCard(stringResource(R.string.gs_lora_governor)) {
        if (lora.meters.isEmpty()) {
            Text(
                stringResource(if (lora.regionCode == null) R.string.gs_lora_governor_none else R.string.gs_lora_governor_unverified),
                style = MaterialTheme.typography.bodyMedium,
                color = AdosTheme.colors.fgSecondary,
            )
        }
        lora.meters.forEach { MeterRow(it) }
    }
}

@Composable
private fun MeterRow(m: RuleMeter) {
    val (title, value, fraction) = when (m) {
        is RuleMeter.Duty -> Triple(
            stringResource(R.string.gs_lora_rule_duty, m.subBand ?: ""),
            listOfNotNull(
                if (m.airtimeMs != null && m.budgetMs != null) "%.1f / %.0f s".format(m.airtimeMs / 1000.0, m.budgetMs / 1000.0) else null,
                m.usedPct?.let { u -> m.limitPct?.let { l -> "%.2f %% / %.1f %%".format(u, l) } },
            ).joinToString(" · ").ifEmpty { null },
            m.fraction,
        )
        is RuleMeter.Dwell -> Triple(
            stringResource(R.string.gs_lora_rule_dwell),
            m.lastTxMs?.let { t ->
                m.limitMs?.let { l ->
                    m.perS?.let { p -> stringResource(R.string.gs_lora_dwell_value_per, t, l, p) } ?: stringResource(R.string.gs_lora_dwell_value, t, l)
                }
            },
            m.fraction,
        )
        is RuleMeter.Power -> Triple(
            stringResource(R.string.gs_lora_rule_power),
            m.configuredMw?.let { c -> m.limitMw?.let { l -> "%.0f / %.0f mW ERP".format(c, l) } },
            m.fraction,
        )
        is RuleMeter.Lbt -> Triple(
            stringResource(R.string.gs_lora_rule_lbt),
            listOfNotNull(
                m.thresholdDbm?.let { "%.0f dBm".format(it) },
                m.listenUs?.let { "%.0f ms".format(it / 1000.0) },
                m.lastRssiDbm?.let { stringResource(R.string.gs_lora_lbt_last, "%.0f".format(it)) },
                m.busyCount?.let { stringResource(R.string.gs_lora_lbt_busy_count, it) },
            ).joinToString(" · ").ifEmpty { null },
            null,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        KeyValueRow(title, value)
        fraction?.let { f -> LinearProgressIndicator(progress = { f.coerceIn(0f, 1f) }, color = meterColor(m.level), modifier = Modifier.fillMaxWidth()) }
        val (level, label) = when {
            m is RuleMeter.Lbt -> when (m.busy) {
                true -> StatusLevel.Warning to R.string.gs_lora_lbt_busy
                false -> StatusLevel.Success to R.string.gs_lora_lbt_clear
                null -> StatusLevel.Neutral to R.string.gs_lora_lbt_unknown
            }
            m.level == MeterLevel.AtLimit -> StatusLevel.Error to R.string.gs_lora_meter_at_limit
            m.level == MeterLevel.Near -> StatusLevel.Warning to R.string.gs_lora_meter_near
            else -> StatusLevel.Success to R.string.gs_lora_meter_ok
        }
        StatusChip(level, stringResource(label))
    }
}

@Composable
private fun meterColor(level: MeterLevel): Color = when (level) {
    MeterLevel.Normal -> AdosTheme.colors.fgSecondary
    MeterLevel.Near -> AdosTheme.colors.statusWarning
    MeterLevel.AtLimit -> AdosTheme.colors.statusError
}

@Composable
private fun RefusalsCard(lora: LoraUi) {
    NodeCard(stringResource(R.string.gs_lora_refusals)) {
        KeyValueRow(stringResource(R.string.gs_lora_tx_frames), lora.txFrames.fmtInt())
        KeyValueRow(stringResource(R.string.gs_lora_refused_total), lora.refusedTotal.fmtInt())
        lora.refusals.forEach { r -> KeyValueRow(ruleName(r.rule), r.count.toString()) }
        KeyValueRow(stringResource(R.string.gs_lora_last_refusal), lora.lastRefusalRule?.let { ruleName(it) })
        KeyValueRow(stringResource(R.string.gs_lora_rx_frames), lora.rxFrames.fmtInt())
        KeyValueRow(stringResource(R.string.gs_lora_crc_errors), lora.crcErrors.fmtInt())
    }
}

@Composable
private fun BeaconsCard(beacons: List<BeaconUi>) {
    NodeCard(stringResource(R.string.gs_lora_beacons)) {
        if (beacons.isEmpty()) {
            Text(stringResource(R.string.gs_lora_beacons_none), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        }
        val now = System.currentTimeMillis()
        beacons.forEach { b ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
                Text(stringResource(R.string.gs_lora_beacon_node, b.nodeId), style = MaterialTheme.typography.titleSmall)
                b.receivedAtMs?.let { Text(stringResource(R.string.gs_lora_beacon_age, ((now - it) / 1000).coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall) }
            }
            KeyValueRow(
                stringResource(R.string.gs_lora_beacon_position),
                b.latDeg?.let { lat -> b.lonDeg?.let { lon -> "%.5f %.5f".format(lat, lon) } },
            )
            KeyValueRow(
                stringResource(R.string.gs_lora_beacon_state),
                listOfNotNull(b.altM.fmt(0, " m"), b.batteryPct.fmtInt(" %"), b.mode).joinToString(" · ").ifEmpty { null },
            )
            KeyValueRow(
                stringResource(R.string.gs_lora_beacon_link),
                listOfNotNull(b.rssiDbm.fmt(0, " dBm"), b.snrDb.fmt(1, " dB")).joinToString(" · ").ifEmpty { null },
            )
        }
    }
}

@Composable
internal fun ruleName(rule: String): String = when (rule) {
    "duty_cycle" -> stringResource(R.string.gs_lora_rule_duty, "").trim()
    "dwell_time" -> stringResource(R.string.gs_lora_rule_dwell)
    "lbt" -> stringResource(R.string.gs_lora_rule_lbt)
    "max_power" -> stringResource(R.string.gs_lora_rule_power)
    "region_unset" -> stringResource(R.string.gs_lora_rule_region)
    else -> rule
}

internal fun band(mhz: List<Double>): String? = if (mhz.size == 2) "%.1f-%.1f MHz".format(mhz[0], mhz[1]) else null

private fun remaining(nextMs: Long?, readAtMs: Long): Long =
    if (nextMs == null) 0 else (nextMs - (System.currentTimeMillis() - readAtMs)).coerceAtLeast(0)

private fun countdown(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

private const val TICK_MS = 1_000L
