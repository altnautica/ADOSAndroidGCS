package com.altnautica.gcs.feature.cockpit.skillbar

import com.altnautica.gcs.core.skills.spec.LayoutSpec

/** Window width class of the cockpit (`layout.json` breakpoints). */
enum class WidthClass(val id: String) { Compact("compact"), Medium("medium"), Expanded("expanded"), Tv("tv") }

/** True for the classes wide enough for the telemetry pane, full rail and range geometry. */
fun WidthClass.isWide(): Boolean = when (this) {
    WidthClass.Compact, WidthClass.Medium -> false
    WidthClass.Expanded, WidthClass.Tv -> true
}

/** Width class for a window [widthDp] wide, by the spec's breakpoints; TV-sized windows are checked first. */
fun widthClassFor(widthDp: Float, layout: LayoutSpec? = null): WidthClass {
    val classes = layout?.breakpoints?.classes
    if (classes != null) {
        val match = classes.firstOrNull { c -> widthDp >= c.min && c.max.let { max -> max == null || widthDp < max + 1 } }
        WidthClass.entries.firstOrNull { it.id == match?.id }?.let { return it }
    }
    return when {
        widthDp >= 1600f -> WidthClass.Tv
        widthDp >= 840f -> WidthClass.Expanded
        widthDp >= 600f -> WidthClass.Medium
        else -> WidthClass.Compact
    }
}
