package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.feature.cockpit.chrome.RoundButton
import com.altnautica.gcs.feature.cockpit.flow.specText

/**
 * Whether the left column offers Land instead of Take off: the pilot's state when it
 * reports, else the heartbeat's arm. An unknown arm state counts as possibly airborne.
 */
internal fun leftColumnLands(pilot: PilotState?, bandArmed: Boolean?): Boolean = pilot?.possiblyAirborne ?: (bandArmed != false)

/** Take off (opens the Launch sheet) or land, return home, pause: the built-ins' own gates and confirms apply. */
@Composable
internal fun LeftColumn(vm: CockpitViewModel, size: Dp, gap: Dp) {
    val pilot by vm.pilotState.collectAsStateWithLifecycle()
    val band by vm.band.collectAsStateWithLifecycle()
    val airborne = leftColumnLands(pilot, band.armed)
    val labels = vm.cockpitSpec.builtins.builtins.associate { it.id to it.label }
    // Icon and a short word under it (`builtin.short.<id>`), so no action is icon-only.
    @Composable fun short(id: String) = specText("builtin.short.$id")
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
        if (airborne) {
            RoundButton(Icons.Outlined.FlightLand, labels[BuiltinIds.LAND].orEmpty(), size, "grid-land", { vm.activate(BuiltinIds.LAND) }, shortLabel = short(BuiltinIds.LAND))
        } else {
            RoundButton(Icons.Outlined.FlightTakeoff, labels[BuiltinIds.TAKEOFF].orEmpty(), size, "grid-takeoff", { vm.openLaunch(true) }, shortLabel = short(BuiltinIds.TAKEOFF))
        }
        RoundButton(Icons.Outlined.Home, labels[BuiltinIds.RTH].orEmpty(), size, "grid-rth", { vm.activate(BuiltinIds.RTH) }, shortLabel = short(BuiltinIds.RTH))
        RoundButton(Icons.Outlined.PauseCircle, labels[BuiltinIds.PAUSE].orEmpty(), size, "grid-pause", { vm.activate(BuiltinIds.PAUSE) }, shortLabel = short(BuiltinIds.PAUSE))
    }
}
