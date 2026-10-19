package com.altnautica.gcs.feature.node.groundstation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.common.DASH
import com.altnautica.gcs.feature.node.common.KeyValueRow
import com.altnautica.gcs.feature.node.common.NodeCard
import com.altnautica.gcs.feature.node.common.arr
import com.altnautica.gcs.feature.node.common.bool
import com.altnautica.gcs.feature.node.common.double
import com.altnautica.gcs.feature.node.common.fmt
import com.altnautica.gcs.feature.node.common.long
import com.altnautica.gcs.feature.node.common.obj
import com.altnautica.gcs.feature.node.common.objects
import com.altnautica.gcs.feature.node.common.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** One WFB adapter row of `GET /api/v1/radio/adapters`. */
data class RadioAdapter(
    val iface: String,
    val role: String?,
    val antenna: String?,
    val txActive: Boolean,
    val rssiDbm: List<Double>,
    val packets: Long?,
    /** May be an estimate when several adapters receive the same stream. */
    val lost: Long?,
    /** Null when FEC recovery is per stream rather than per adapter (multi-adapter receive). */
    val fecRec: Long?,
)

data class RadioDiversity(val rxAdapters: Int?, val combinedLossPct: Double?, val bestSingleLossPct: Double?)

/** The station's receive plane. [present] is false when the node has no receive plane at all. */
data class RadioAdapters(val present: Boolean, val adapters: List<RadioAdapter>, val diversity: RadioDiversity?) {
    companion object {
        fun parse(o: JsonObject): RadioAdapters {
            val list = o.arr("adapters")?.objects().orEmpty().mapNotNull { a ->
                val iface = a.str("iface") ?: return@mapNotNull null
                RadioAdapter(
                    iface = iface,
                    role = a.str("role"),
                    antenna = a.str("antenna"),
                    txActive = a.bool("tx_active") == true,
                    rssiDbm = a.arr("rssi_dbm")?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty(),
                    packets = a.long("packets"),
                    lost = a.long("lost"),
                    fecRec = a.long("fec_rec"),
                )
            }
            // An older agent without the field still reports adapters; treat a populated list as present.
            val present = o.bool("present") ?: list.isNotEmpty()
            val diversity = o.obj("diversity")?.let {
                RadioDiversity(it.long("rx_adapters")?.toInt(), it.double("combined_loss_pct"), it.double("best_single_loss_pct"))
            }
            return RadioAdapters(present, if (present) list else emptyList(), diversity.takeIf { present })
        }
    }
}

/** Per-adapter cards; nothing is drawn when the station has no receive plane. */
@Composable
internal fun RadioAdaptersView(a: RadioAdapters) {
    if (!a.present || a.adapters.isEmpty()) return
    a.adapters.forEach { ad ->
        NodeCard(ad.iface) {
            KeyValueRow(stringResource(R.string.gs_adapter_role), listOfNotNull(ad.role, stringResource(R.string.gs_adapter_tx).takeIf { ad.txActive }).joinToString(" · ").ifEmpty { null })
            KeyValueRow(stringResource(R.string.gs_adapter_antenna), ad.antenna)
            KeyValueRow(stringResource(R.string.gs_adapter_rssi), ad.rssiDbm.takeIf { it.isNotEmpty() }?.joinToString(" / ") { "%.0f".format(it) }?.let { "$it dBm" })
            KeyValueRow(stringResource(R.string.gs_adapter_packets), ad.packets?.let { p -> "$p / ${ad.lost ?: DASH}" })
            KeyValueRow(stringResource(R.string.gs_adapter_fec), ad.fecRec?.toString() ?: stringResource(R.string.gs_adapter_fec_per_stream))
        }
    }
    a.diversity?.let { d ->
        NodeCard(stringResource(R.string.gs_adapter_diversity)) {
            KeyValueRow(stringResource(R.string.gs_adapter_rx_count), d.rxAdapters?.toString())
            KeyValueRow(stringResource(R.string.gs_adapter_combined_loss), d.combinedLossPct.fmt(1, " %"))
            KeyValueRow(stringResource(R.string.gs_adapter_best_loss), d.bestSingleLossPct.fmt(1, " %"))
        }
    }
}
