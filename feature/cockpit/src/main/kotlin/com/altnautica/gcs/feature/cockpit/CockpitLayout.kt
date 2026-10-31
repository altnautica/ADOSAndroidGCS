package com.altnautica.gcs.feature.cockpit

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Named cockpit spacings, so each clearance is defined once. */
internal object CockpitLayout {
    /** Space kept free above the bottom edge for the Skill Bar. */
    val SkillBarClearance = 112.dp

    /** Space kept free below the safety band for the inset row (minimap, tabs, strip). */
    val ZoneTopClearance = 140.dp

    private val InsetMin = 160.dp
    private val InsetMax = 280.dp
    private const val INSET_FRACTION = 0.26f
    const val INSET_ASPECT = 16f / 10f

    /** Minimap inset width for a cockpit [containerWidth] wide. */
    fun insetWidth(containerWidth: Dp): Dp = (containerWidth * INSET_FRACTION).coerceIn(InsetMin, InsetMax)
}
