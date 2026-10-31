@file:OptIn(ExperimentalMaterial3Api::class)

package com.altnautica.gcs.feature.plan

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.NumberSettingRow
import com.altnautica.gcs.core.designsystem.component.SectionHeader
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.PlanItem
import com.altnautica.gcs.feature.plan.model.SpeedType
import kotlin.math.roundToInt

@Composable
internal fun EditorHeader(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title, Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.plan_close_editor), tint = AdosTheme.colors.fgSecondary)
        }
    }
}

/** Formats a whole-number value with a unit string resource (`%1$d m`). */
@Composable
internal fun unitFormatter(@StringRes id: Int): (Int) -> String {
    val resources = LocalResources.current
    return remember(resources, id) { { value: Int -> resources.getString(id, value) } }
}

@Composable
internal fun metresFormatter() = unitFormatter(R.string.plan_unit_m)

@Composable
private fun refLabel(ref: AltitudeRef) = stringResource(
    when (ref) {
        AltitudeRef.Relative -> R.string.plan_ref_rel
        AltitudeRef.Msl -> R.string.plan_ref_msl
        AltitudeRef.Terrain -> R.string.plan_ref_terrain
    },
)

@Composable
private fun speedTypeLabel(type: SpeedType) = stringResource(
    when (type) {
        SpeedType.Airspeed -> R.string.plan_speed_airspeed
        SpeedType.Groundspeed -> R.string.plan_speed_groundspeed
        SpeedType.Climb -> R.string.plan_speed_climb
        SpeedType.Descent -> R.string.plan_speed_descent
    },
)

/** REL, MSL or terrain-relative altitude; terrain explains what it needs and what the profile shows. */
@Composable
internal fun AltitudeRefSelector(selected: AltitudeRef?, onSelect: (AltitudeRef) -> Unit) {
    val options = AltitudeRef.entries
    Column {
        Text(stringResource(R.string.plan_item_altitude_ref), style = MaterialTheme.typography.labelMedium, color = AdosTheme.colors.fgSecondary)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, ref ->
                SegmentedButton(
                    selected = ref == selected,
                    onClick = { onSelect(ref) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(refLabel(ref)) },
                )
            }
        }
        if (selected == AltitudeRef.Terrain) {
            Text(stringResource(R.string.plan_terrain_hint), style = MaterialTheme.typography.bodySmall, color = AdosTheme.colors.fgSecondary)
        }
    }
}

@Composable
internal fun ItemEditor(item: PlanItem, number: Int, actions: PlannerActions) {
    val colors = AdosTheme.colors
    EditorHeader(stringResource(R.string.plan_item_title, number)) { actions.onSelect(null) }
    val known = item.planCommand
    if (known != null) {
        // Only commands of the item's own class: a positional item never turns into a non-positional one.
        val options = known.swappable
        val labels = options.associateWith { commandLabel(it) }
        SelectSettingRow(
            title = stringResource(R.string.plan_item_command),
            options = options,
            selected = known,
            optionLabel = { labels.getValue(it) },
            onSelect = { actions.onSetCommand(item.id, it) },
        )
    } else {
        Text(stringResource(R.string.plan_item_command_other, item.command), style = MaterialTheme.typography.bodyMedium, color = colors.fgPrimary)
    }
    Text(
        item.position?.let { stringResource(R.string.plan_item_position, it.latDeg, it.lonDeg) } ?: stringResource(R.string.plan_item_no_position),
        style = MaterialTheme.typography.bodySmall,
        color = colors.fgSecondary,
    )
    if (item.altitudeRef != null && item.command != MavCmd.NAV_RETURN_TO_LAUNCH) {
        NumberSettingRow(
            title = stringResource(R.string.plan_item_altitude),
            value = item.z.roundToInt(),
            onValueChange = { actions.onSetAltitude(item.id, it.toFloat()) },
            range = -500..10_000,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
            format = metresFormatter(),
        )
        AltitudeRefSelector(item.altitudeRef) { actions.onSetAltitudeRef(item.id, it) }
    }
    if (MavCmd.isNav(item.command)) {
        val noSpeed = stringResource(R.string.plan_item_speed_none)
        val speedFmt = unitFormatter(R.string.plan_unit_ms)
        NumberSettingRow(
            title = stringResource(R.string.plan_item_speed),
            value = item.speedMs?.roundToInt() ?: 0,
            onValueChange = { actions.onSetSpeed(item.id, it.toFloat().takeIf { v -> v > 0f }) },
            range = 0..60,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
            format = { if (it == 0) noSpeed else speedFmt(it) },
        )
    }
    CommandParams(item, actions)
    AdosButton(stringResource(R.string.plan_item_delete), onClick = { actions.onDeleteItem(item.id) }, variant = AdosButtonVariant.Danger)
}

/** Per-command parameters: hold and loiter times, turns, speed changes and the camera shutter. */
@Composable
private fun CommandParams(item: PlanItem, actions: PlannerActions) {
    val secondsFmt = unitFormatter(R.string.plan_unit_s)
    when (item.command) {
        MavCmd.NAV_WAYPOINT, MavCmd.NAV_LOITER_TIME -> NumberSettingRow(
            title = stringResource(if (item.command == MavCmd.NAV_WAYPOINT) R.string.plan_item_hold else R.string.plan_item_loiter_time),
            value = item.param1.takeIf { it.isFinite() }?.roundToInt() ?: 0,
            onValueChange = { actions.onSetParam(item.id, 1, it.toDouble()) },
            range = 0..3600,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
            format = secondsFmt,
        )
        MavCmd.NAV_LOITER_TURNS -> NumberSettingRow(
            title = stringResource(R.string.plan_item_turns),
            value = item.param1.takeIf { it.isFinite() }?.roundToInt() ?: 1,
            onValueChange = { actions.onSetParam(item.id, 1, it.toDouble()) },
            range = 1..100,
            decrementDescription = stringResource(R.string.plan_decrease),
            incrementDescription = stringResource(R.string.plan_increase),
        )
        MavCmd.DO_CHANGE_SPEED -> {
            val types = SpeedType.entries
            val labels = types.associateWith { speedTypeLabel(it) }
            SelectSettingRow(
                title = stringResource(R.string.plan_item_speed_type),
                options = types,
                selected = SpeedType.fromCode(item.param1),
                optionLabel = { labels.getValue(it) },
                onSelect = { actions.onSetParam(item.id, 1, it.code.toDouble()) },
            )
            NumberSettingRow(
                title = stringResource(R.string.plan_item_new_speed),
                value = item.param2.takeIf { it.isFinite() }?.roundToInt() ?: 0,
                onValueChange = { actions.onSetParam(item.id, 2, it.toDouble()) },
                range = 1..60,
                decrementDescription = stringResource(R.string.plan_decrease),
                incrementDescription = stringResource(R.string.plan_increase),
                format = unitFormatter(R.string.plan_unit_ms),
            )
        }
        // DO_DIGICAM_CONTROL param5 (x) is "shoot": 1 takes a picture, 0 does not.
        MavCmd.DO_DIGICAM_CONTROL -> SwitchSettingRow(
            title = stringResource(R.string.plan_item_shoot),
            checked = item.x != 0.0,
            onCheckedChange = { actions.onSetParam(item.id, 5, if (it) 1.0 else 0.0) },
        )
    }
}
