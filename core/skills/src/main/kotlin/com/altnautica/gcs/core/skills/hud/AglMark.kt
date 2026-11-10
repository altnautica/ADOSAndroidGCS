package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.spec.SetupStringsSpec

/**
 * The mark beside a bottom-row AGL that is not a ground measurement: `agl.source.home` for the
 * pilot's EKF height above home, `agl.source.baro` for the follow kind's barometer; null for a
 * measured height.
 */
fun SetupStringsSpec.aglMark(home: Boolean = false, baro: Boolean = false): String? = when {
    home -> SpecText(this).text("agl.source.home")
    baro -> SpecText(this).text("agl.source.baro")
    else -> null
}
