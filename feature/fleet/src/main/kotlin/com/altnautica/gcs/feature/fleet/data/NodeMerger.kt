package com.altnautica.gcs.feature.fleet.data

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach

/**
 * Folds the node lists of every link family into one list keyed by [DeviceId].
 *
 * Reaches are unioned and ordered so commandable links come first: direct FC,
 * LAN, via ground station, phone radio, live cloud, cloud queue. A blocked
 * reach is kept only while the node has no usable reach, so the reason stays
 * visible without hiding a working link. Video source choice is separate and
 * prefers the phone radio (see the video source selector).
 */
object NodeMerger {

    fun merge(lists: List<List<NodeSummary>>): List<NodeSummary> {
        val byId = LinkedHashMap<DeviceId, MutableList<NodeSummary>>()
        for (list in lists) {
            for (node in list) byId.getOrPut(node.id) { mutableListOf() } += node
        }
        return byId.values.map(::mergeOne).sortedWith(FLEET_ORDER)
    }

    /** Rank of a reach in the commandable-first order; lower is better. */
    fun rank(reach: Reach): Int = when (reach) {
        Reach.DirectFc -> 0
        Reach.Lan -> 1
        is Reach.ViaGround -> 2
        Reach.PhoneRadio -> 3
        Reach.CloudLive -> 4
        Reach.CloudQueue -> 5
        is Reach.Blocked -> 6
    }

    fun orderReaches(reaches: Collection<Reach>): List<Reach> {
        val distinct = reaches.distinct().sortedBy(::rank)
        val usable = distinct.filterNot { it is Reach.Blocked }
        return usable.ifEmpty { distinct }
    }

    private fun mergeOne(parts: List<NodeSummary>): NodeSummary {
        if (parts.size == 1) return parts[0].copy(reaches = orderReaches(parts[0].reaches))
        // The part holding the best reach speaks first for descriptive fields.
        val ordered = parts.sortedBy { p -> p.reaches.minOfOrNull(::rank) ?: Int.MAX_VALUE }
        return NodeSummary(
            id = ordered[0].id,
            name = ordered.firstOrNull { it.name.isNotBlank() }?.name ?: ordered[0].id.value,
            profile = ordered.firstOrNull { it.profile != NodeProfile.Unknown }?.profile ?: NodeProfile.Unknown,
            reaches = orderReaches(ordered.flatMap { it.reaches }),
            liveness = ordered.minOf { it.liveness.ordinal }.let { Liveness.entries[it] },
            agentVersion = ordered.firstNotNullOfOrNull { it.agentVersion },
            fcVariant = ordered.firstOrNull { it.fcVariant != FcVariant.Unknown }?.fcVariant ?: FcVariant.Unknown,
            carriedDroneId = ordered.firstNotNullOfOrNull { it.carriedDroneId },
            lastSeenMs = ordered.mapNotNull { it.lastSeenMs }.maxOrNull(),
        )
    }

    /** Live nodes first, then stale, then offline; alphabetical inside each group. */
    private val FLEET_ORDER = compareBy<NodeSummary>({ it.liveness.ordinal }, { it.name.lowercase() }, { it.id.value })
}
