package com.altnautica.gcs.feature.cockpit

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** Named cockpit spacings, so each clearance is defined once. */
internal object CockpitLayout {
    private val SkillBarClearanceDefault = 112.dp
    private val SkillBarClearanceTv = 140.dp

    /** Space kept free above the bottom edge for the Skill Bar. */
    fun skillBarClearance(width: WidthClass): Dp = when (width) {
        WidthClass.Compact, WidthClass.Medium, WidthClass.Expanded -> SkillBarClearanceDefault
        WidthClass.Tv -> SkillBarClearanceTv
    }

    /** Space kept free below the safety band for the inset row (minimap, tabs, strip). */
    val ZoneTopClearance = 140.dp

    private val InsetMin = 160.dp
    private val InsetMax = 280.dp
    private val InsetMinTv = 200.dp
    private val InsetMaxTv = 360.dp
    private const val INSET_FRACTION = 0.26f
    const val INSET_ASPECT = 16f / 10f

    /** Minimap inset width for a cockpit [containerWidth] wide. */
    fun insetWidth(containerWidth: Dp, width: WidthClass): Dp = when (width) {
        WidthClass.Compact, WidthClass.Medium, WidthClass.Expanded ->
            (containerWidth * INSET_FRACTION).coerceIn(InsetMin, InsetMax)
        WidthClass.Tv -> (containerWidth * INSET_FRACTION).coerceIn(InsetMinTv, InsetMaxTv)
    }
}
