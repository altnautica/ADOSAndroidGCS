package com.altnautica.gcs.feature.cockpit.confirm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.feature.cockpit.flow.specText
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Activation argument of Fly Here, for its confirm only: metres from the drone to the point
 * when the drone's position is known. The skill itself never reads it.
 */
const val ARG_FLY_HERE_DISTANCE_M = "flyHereDistanceM"

/**
 * What a Fly Here commits to, from the request args: the point, its distance from the drone
 * and the height above home (`map.fly_here.point/distance/height`), then the line saying it
 * takes over from a running mission or skill. An unknown value reads as a dash.
 */
@Composable
internal fun FlyHereConfirmBody(args: Map<String, Any?>) {
    val lat = (args[SkillArgs.LAT] as? Number)?.toDouble()
    val lon = (args[SkillArgs.LON] as? Number)?.toDouble()
    val alt = (args[SkillArgs.ALTITUDE_M] as? Number)?.toFloat()
    val dist = (args[ARG_FLY_HERE_DISTANCE_M] as? Number)?.toFloat()
    Column(Modifier.fillMaxWidth().testTag("fly-here-confirm"), verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1)) {
        FactRow("map.fly_here.point", if (lat != null && lon != null) flyHerePoint(lat, lon) else DASH)
        FactRow("map.fly_here.distance", dist?.let { specText("map.fly_here.distance_value", "dist" to it.roundToInt().toString()) } ?: DASH)
        FactRow("map.fly_here.height", alt?.let { specText("map.fly_here.height_value", "alt" to it.roundToInt().toString()) } ?: DASH)
        Text(specText("map.fly_here.takes_over"), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.statusWarning)
    }
}

@Composable
private fun FactRow(labelKey: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3)) {
        Text(specText(labelKey), style = MaterialTheme.typography.bodyMedium, color = AdosTheme.colors.fgSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A point as the other surfaces write it: latitude, longitude to six decimals. */
internal fun flyHerePoint(lat: Double, lon: Double): String =
    String.format(Locale.ROOT, "%.6f, %.6f", lat, lon)

private const val DASH = "—"
