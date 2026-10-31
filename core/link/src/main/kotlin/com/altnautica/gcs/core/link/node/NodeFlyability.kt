package com.altnautica.gcs.core.link.node

import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach

/**
 * True when the cockpit can fly this node: its best reach is usable and it is
 * (or carries) a vehicle. A ground station flies only with a carried drone; a
 * node of unknown profile flies only over a direct flight-controller link.
 */
fun NodeSummary.canFly(): Boolean {
    val usable = reaches.firstOrNull()?.let { it !is Reach.Blocked } == true
    val flyable = when (profile) {
        NodeProfile.Drone -> true
        NodeProfile.GroundStation -> carriedDroneId != null
        NodeProfile.Workstation, NodeProfile.Visor -> false
        NodeProfile.Unknown -> Reach.DirectFc in reaches
    }
    return usable && flyable
}
