package com.altnautica.gcs.feature.cockpit.chrome

import com.altnautica.gcs.core.skills.spec.DividerSpec
import com.altnautica.gcs.core.skills.spec.ViewsSpec

/** The split view's divider from `views.json` (`split.divider`); 0.5 within 0.3..0.7 when the spec names none. */
internal fun splitDivider(views: ViewsSpec): DividerSpec =
    views.views.firstOrNull { it.id == "split" }?.divider ?: DividerSpec(draggable = true, default = 0.5f, min = 0.3f, max = 0.7f)

/** A ratio held inside the divider's range; a non-finite ratio falls back to the default. */
internal fun DividerSpec.clamp(ratio: Float): Float = if (ratio.isFinite()) ratio.coerceIn(min, max) else default
