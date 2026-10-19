package com.altnautica.gcs.core.extensions.host

import com.altnautica.gcs.core.extensions.manifest.ExtensionDetail
import com.altnautica.gcs.core.extensions.manifest.ExtensionSlots
import com.altnautica.gcs.core.extensions.manifest.GcsIsolation
import com.altnautica.gcs.core.extensions.manifest.SlotContribution
import com.altnautica.gcs.core.model.NodeProfile

/** One extension contribution to mount in a slot. */
data class SlotMount(
    val pluginId: String,
    val pluginName: String,
    val contribution: SlotContribution,
    val inline: Boolean,
) {
    val key: String get() = "$pluginId::${contribution.slot}::${contribution.panelId}"
}

object SlotMounts {
    const val DEFAULT_WIDGET_ZONE = "bottom-left"

    fun wireProfile(p: NodeProfile): String? = when (p) {
        NodeProfile.Drone -> "drone"
        NodeProfile.GroundStation -> "ground-station"
        NodeProfile.Workstation -> "workstation"
        NodeProfile.Visor -> "visor"
        NodeProfile.Unknown -> null
    }

    /**
     * Contributions of enabled extensions at [slot] that the node granted the slot's
     * capability to, offered on [profile] and, for widgets, placed in [zone]. Ordered by
     * the contribution's `order`, then extension name.
     */
    fun resolve(
        extensions: List<ExtensionDetail>,
        slot: String,
        profile: NodeProfile,
        zone: String? = null,
    ): List<SlotMount> {
        val capability = ExtensionSlots.capability(slot)
        val nodeProfile = wireProfile(profile)
        return extensions.asSequence()
            .filter { it.status.isEnabled && capability in it.grantedCapabilities }
            .flatMap { ext ->
                ext.contributions.slots.asSequence()
                    .filter { it.slot == slot }
                    .filter { ExtensionSlots.offersOnProfile(slot, it.profile, nodeProfile) }
                    .filter { zone == null || slot != ExtensionSlots.COCKPIT_WIDGET || (it.zone ?: DEFAULT_WIDGET_ZONE) == zone }
                    .map { SlotMount(ext.pluginId, ext.name, it, ext.isolation == GcsIsolation.Inline) }
            }
            .sortedWith(compareBy<SlotMount> { it.contribution.order ?: Double.MAX_VALUE }.thenBy { it.pluginName })
            .toList()
    }
}
