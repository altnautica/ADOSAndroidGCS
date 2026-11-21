package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.SensingTier

/**
 * The sensing chip's text, a port of the shared web module `hud/sensing.ts`: what the pilot
 * sees obstacles with and the speed that still stops inside the range it sees clear
 * (`Sensing: 3D lidar · ≤ 4.2 m/s`). Text from the spec's string table only.
 */
class SensingText(private val text: SpecText) {

    /** `3D lidar`, `Depth camera`, `Camera` or `2D lidar`. */
    fun tier(t: SensingTier): String = text.text("pilot.sensing.tier.${t.wire}")

    /** The chip text while the pilot reports its sensing, else null (an older agent). */
    fun chip(p: PilotState?): String? {
        val s = p?.sensing ?: return null
        return text.text(
            "pilot.sensing.chip",
            mapOf("tier" to tier(s.tier), "v" to ConfinedText.speedCapText(s.speedLimitMps.toDouble())),
        )
    }
}
