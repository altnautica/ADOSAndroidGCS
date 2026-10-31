package com.altnautica.gcs.feature.settings.controller

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.settings.R

/** One row of a button map: the inputs, what they do and, for flight actions, the confirm tier. */
internal data class MapRow(val inputs: List<MapInput>, @StringRes val action: Int, @StringRes val tier: Int? = null)

/** A titled button map with an optional note under it. */
internal data class ButtonMap(@StringRes val title: Int, val rows: List<MapRow>, @StringRes val note: Int? = null)

private fun b(vararg buttons: ConsoleButton) = buttons.map { MapInput.Button(it) }

private fun n(@StringRes label: Int) = listOf(MapInput.Named(label))

/** Everywhere outside the cockpit's flight input. */
private val UiMap = ButtonMap(
    title = R.string.settings_controller_map_ui,
    rows = listOf(
        MapRow(n(R.string.settings_controller_input_dpad) + n(R.string.settings_controller_input_left_stick), R.string.settings_controller_ui_focus),
        MapRow(b(ConsoleButton.Confirm), R.string.settings_controller_ui_activate),
        MapRow(b(ConsoleButton.Back), R.string.settings_controller_ui_back),
        MapRow(b(ConsoleButton.Details), R.string.settings_controller_ui_details),
        MapRow(b(ConsoleButton.More), R.string.settings_controller_ui_more),
        MapRow(b(ConsoleButton.PrevTab, ConsoleButton.NextTab), R.string.settings_controller_ui_tabs),
        MapRow(b(ConsoleButton.PrevPage, ConsoleButton.NextPage), R.string.settings_controller_ui_pages),
        MapRow(b(ConsoleButton.Menu), R.string.settings_controller_ui_menu),
        MapRow(b(ConsoleButton.Home), R.string.settings_controller_ui_home),
        MapRow(b(ConsoleButton.Capture, ConsoleButton.StickL, ConsoleButton.StickR) + n(R.string.settings_controller_input_touchpad), R.string.settings_controller_ui_nothing),
    ),
)

/** The cockpit with the default Skill Bar loadout. */
private val CockpitMap = ButtonMap(
    title = R.string.settings_controller_map_cockpit,
    rows = listOf(
        MapRow(b(ConsoleButton.Confirm), R.string.settings_controller_cockpit_arm, R.string.settings_controller_tier_arm),
        MapRow(b(ConsoleButton.More), R.string.settings_controller_cockpit_pause, R.string.settings_controller_tier_tap),
        MapRow(b(ConsoleButton.PrevTab), R.string.settings_controller_cockpit_land, R.string.settings_controller_tier_hold),
        MapRow(b(ConsoleButton.NextTab), R.string.settings_controller_cockpit_rtl, R.string.settings_controller_tier_hold),
        MapRow(b(ConsoleButton.PrevPage), R.string.settings_controller_cockpit_takeoff, R.string.settings_controller_tier_hold),
        MapRow(b(ConsoleButton.NextPage), R.string.settings_controller_cockpit_loiter, R.string.settings_controller_tier_tap),
        MapRow(b(ConsoleButton.Details), R.string.settings_controller_cockpit_swap),
        MapRow(b(ConsoleButton.Back), R.string.settings_controller_cockpit_back),
        MapRow(b(ConsoleButton.StickL, ConsoleButton.StickR), R.string.settings_controller_cockpit_stick),
        MapRow(n(R.string.settings_controller_input_dpad_left_right), R.string.settings_controller_cockpit_stream),
        MapRow(n(R.string.settings_controller_input_dpad_up), R.string.settings_controller_cockpit_modes),
        MapRow(n(R.string.settings_controller_input_dpad_down), R.string.settings_controller_cockpit_drawer),
        MapRow(b(ConsoleButton.Menu), R.string.settings_controller_cockpit_menu),
        MapRow(b(ConsoleButton.Home), R.string.settings_controller_ui_home),
        MapRow(b(ConsoleButton.Capture), R.string.settings_controller_cockpit_record),
        MapRow(n(R.string.settings_controller_input_left_stick), R.string.settings_controller_cockpit_left_stick),
        MapRow(n(R.string.settings_controller_input_right_stick), R.string.settings_controller_cockpit_right_stick),
        MapRow(n(R.string.settings_controller_input_none), R.string.settings_controller_cockpit_kill, R.string.settings_controller_tier_guarded),
    ),
    note = R.string.settings_controller_cockpit_note,
)

/** The planner while the map has focus. */
private val PlannerMap = ButtonMap(
    title = R.string.settings_controller_map_planner,
    rows = listOf(
        MapRow(n(R.string.settings_controller_input_left_stick), R.string.settings_controller_planner_pan),
        MapRow(b(ConsoleButton.PrevPage, ConsoleButton.NextPage), R.string.settings_controller_planner_zoom),
        MapRow(b(ConsoleButton.Confirm), R.string.settings_controller_planner_place),
        MapRow(b(ConsoleButton.Details), R.string.settings_controller_planner_actions),
        MapRow(b(ConsoleButton.More), R.string.settings_controller_planner_grab),
        MapRow(b(ConsoleButton.PrevTab, ConsoleButton.NextTab), R.string.settings_controller_planner_waypoints),
        MapRow(b(ConsoleButton.Back), R.string.settings_controller_planner_back),
        MapRow(n(R.string.settings_controller_input_dpad_left_right), R.string.settings_controller_planner_panes),
    ),
)

internal val ButtonMaps = listOf(UiMap, CockpitMap, PlannerMap)

/** Every button map as read-only list items. */
internal fun LazyListScope.buttonMapItems() {
    ButtonMaps.forEach { map ->
        item(key = "map-${map.title}") { SectionHeader(stringResource(map.title)) }
        itemsIndexed(map.rows, key = { index, _ -> "map-${map.title}-$index" }) { _, row -> MapRowItem(row) }
        map.note?.let { note ->
            item(key = "map-${map.title}-note") {
                Text(
                    stringResource(note),
                    style = MaterialTheme.typography.bodySmall,
                    color = AdosTheme.colors.fgSecondary,
                    modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2),
                )
            }
        }
    }
}

@Composable
private fun MapRowItem(row: MapRow) {
    val colors = AdosTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S1)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
    ) {
        Row(Modifier.width(InputColumnWidth), horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1), verticalAlignment = Alignment.CenterVertically) {
            row.inputs.forEach { MapInputGlyph(it) }
        }
        Text(stringResource(row.action), style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary, modifier = Modifier.weight(1f))
        row.tier?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary) }
    }
}

private val InputColumnWidth = 144.dp
