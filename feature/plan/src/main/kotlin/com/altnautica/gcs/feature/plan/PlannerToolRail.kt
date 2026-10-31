@file:OptIn(ExperimentalMaterial3Api::class)

package com.altnautica.gcs.feature.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.CenterFocusWeak
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.console.consoleFocus
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

/** Width of the landscape rail; also the height of the portrait row. */
internal val ToolRailSize = 64.dp

private const val SELECTED_FILL_ALPHA = 0.12f

private val Tools = listOf(
    Triple(EditTool.Waypoint, Icons.Outlined.AddLocationAlt, R.string.plan_tool_waypoint),
    Triple(EditTool.Survey, Icons.Outlined.GridOn, R.string.plan_tool_survey),
    Triple(EditTool.FencePolygon, Icons.Outlined.Pentagon, R.string.plan_tool_fence_polygon),
    Triple(EditTool.FenceCircle, Icons.Outlined.RadioButtonUnchecked, R.string.plan_tool_fence_circle),
    Triple(EditTool.Rally, Icons.Outlined.Route, R.string.plan_tool_rally),
)

/** Camera commands the rail runs on the map. */
internal class RailCameraActions(val onFit: () -> Unit, val onZoomIn: () -> Unit, val onZoomOut: () -> Unit)

/**
 * Tool rail: the edit tools as one choice group, then undo, redo, fit and zoom.
 * Vertical beside the map in landscape, a horizontal row under it in portrait.
 */
@Composable
internal fun PlannerToolRail(
    state: PlannerUiState,
    actions: PlannerActions,
    camera: RailCameraActions,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AdosTheme.colors
    val content: @Composable () -> Unit = {
        val toolsDescription = stringResource(R.string.plan_tools_description)
        val group: @Composable () -> Unit = {
            Tools.forEach { (tool, icon, label) ->
                RailButton(
                    icon = icon,
                    label = stringResource(label),
                    vertical = vertical,
                    selected = state.tool == tool,
                    onClick = { actions.onTool(tool) },
                )
            }
        }
        val groupModifier = Modifier.selectableGroup().semantics { contentDescription = toolsDescription }
        if (vertical) Column(groupModifier, horizontalAlignment = Alignment.CenterHorizontally) { group() } else Row(groupModifier) { group() }
        RailDivider(vertical)
        RailButton(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.plan_action_undo), vertical, enabled = state.canUndo, onClick = actions.onUndo)
        RailButton(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.plan_action_redo), vertical, enabled = state.canRedo, onClick = actions.onRedo)
        RailButton(Icons.Outlined.CenterFocusWeak, stringResource(R.string.plan_action_fit), vertical, onClick = camera.onFit)
        RailButton(Icons.Outlined.ZoomIn, stringResource(R.string.plan_action_zoom_in), vertical, onClick = camera.onZoomIn)
        RailButton(Icons.Outlined.ZoomOut, stringResource(R.string.plan_action_zoom_out), vertical, onClick = camera.onZoomOut)
    }
    if (vertical) {
        Column(
            modifier
                .width(ToolRailSize)
                .fillMaxHeight()
                .background(colors.bgSurface)
                .verticalScroll(rememberScrollState())
                .padding(vertical = AdosSpacing.S2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
        ) { content() }
    } else {
        Row(
            modifier
                .fillMaxWidth()
                .height(ToolRailSize)
                .background(colors.bgSurface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = AdosSpacing.S2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
        ) { content() }
    }
}

/**
 * One rail icon button with a tooltip. With [selected] non-null it is a choice in the
 * tool group (radio semantics, accent tint when chosen); otherwise a plain action.
 */
@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    vertical: Boolean,
    selected: Boolean? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = AdosTheme.colors
    val shape = RoundedCornerShape(AdosRadius.Lg)
    val chosen = selected == true
    val tint = when {
        !enabled -> colors.fgTertiary
        chosen -> colors.accentPrimary
        else -> colors.fgPrimary
    }
    val interaction = if (selected != null) {
        Modifier.selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } else {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            if (vertical) TooltipAnchorPosition.Right else TooltipAnchorPosition.Above,
        ),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        // A focusable tooltip window would take input away from the app's controller routing.
        focusable = false,
    ) {
        Box(
            Modifier
                .size(AdosSize.TouchMin)
                .consoleFocus(shape)
                .clip(shape)
                .background(if (chosen) colors.accentPrimary.copy(alpha = SELECTED_FILL_ALPHA) else Color.Transparent)
                .then(interaction)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(AdosSize.IconLg))
        }
    }
}

@Composable
private fun RailDivider(vertical: Boolean) {
    val color = AdosTheme.colors.borderDefault
    if (vertical) {
        Box(Modifier.padding(vertical = AdosSpacing.S1).width(32.dp).height(1.dp).background(color))
    } else {
        Box(Modifier.padding(horizontal = AdosSpacing.S1).height(32.dp).width(1.dp).background(color))
    }
}
