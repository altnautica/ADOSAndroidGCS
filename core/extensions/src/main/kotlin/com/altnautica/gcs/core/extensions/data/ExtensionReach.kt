package com.altnautica.gcs.core.extensions.data

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach

/** How the app can reach a node's agent for extension routes. */
sealed interface ExtensionReach {
    /** Agent on the local network. */
    data object Lan : ExtensionReach

    /** Drone reached through the ground station's relay proxy. */
    data class ViaGround(val groundId: DeviceId) : ExtensionReach

    /** Only a cloud link exists: extension config writes have no path. */
    data object CloudOnly : ExtensionReach

    /** No agent path at all (direct FC link, phone radio, offline, unpaired). */
    data object Unreachable : ExtensionReach

    val hasAgentPath: Boolean get() = this is Lan || this is ViaGround

    companion object {
        /** Reason a cloud-only skill or write reports (rendered verbatim by the Skill Bar). */
        const val REASON_LAN_OR_GROUND_REQUIRED = "Extensions need a LAN or ground-station link"

        /** Reason when no agent path exists at all. */
        const val REASON_NO_AGENT = "No agent link to this node"

        /** Best agent path among [reaches] (best-first): LAN, then ground relay. */
        fun classify(reaches: List<Reach>): ExtensionReach {
            reaches.firstOrNull { it is Reach.Lan }?.let { return Lan }
            reaches.firstNotNullOfOrNull { it as? Reach.ViaGround }?.let { return ViaGround(it.groundId) }
            if (reaches.any { it is Reach.CloudLive || it is Reach.CloudQueue }) return CloudOnly
            return Unreachable
        }

        fun reasonKey(reach: ExtensionReach): String? = when (reach) {
            Lan, is ViaGround -> null
            CloudOnly -> REASON_LAN_OR_GROUND_REQUIRED
            Unreachable -> REASON_NO_AGENT
        }
    }
}
