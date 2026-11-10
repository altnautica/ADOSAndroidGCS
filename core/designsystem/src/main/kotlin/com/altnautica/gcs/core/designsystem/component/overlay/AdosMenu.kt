package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.console.AdosListItem
import com.altnautica.gcs.core.designsystem.component.console.ConsolePreview
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import kotlin.math.roundToInt

private val MenuMinWidth = 200.dp
private val MenuMaxWidth = 360.dp
private val MenuMargin = 8.dp

/** Open state and anchor bounds of an [AdosMenu]. */
@Stable
class AdosMenuState {
    var expanded by mutableStateOf(false)
    internal var anchor: Rect? by mutableStateOf(null)
}

@Composable
fun rememberAdosMenuState(): AdosMenuState = remember { AdosMenuState() }

/** Records this element's bounds as the anchor the menu opens under. */
fun Modifier.adosMenuAnchor(state: AdosMenuState): Modifier =
    onGloballyPositioned { state.anchor = it.boundsInRoot() }

/** One menu row; [onClick] runs after the menu has closed. */
@Immutable
data class AdosMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/**
 * Popup menu drawn in the app window through the overlay host, under its anchor
 * (see [adosMenuAnchor]) and flipped above it when there is no room below. Tapping
 * outside, system back and the controller back button close it. Focus is trapped
 * inside and starts on the first enabled item.
 */
@Composable
fun AdosMenu(state: AdosMenuState, items: List<AdosMenuItem>) {
    if (!state.expanded) return
    Overlay(key = state) {
        val colors = AdosTheme.colors
        val requester = remember { FocusRequester() }
        val close = { state.expanded = false }
        OverlayBackHandler(close)
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(top = LocalOverlayTopInset.current)) { OverlayScrim(Color.Transparent, close) }
            MenuPlacement(anchor = state.anchor) {
                Surface(
                    modifier = Modifier.widthIn(min = MenuMinWidth, max = MenuMaxWidth).overlayFocusTrap(requester),
                    shape = RoundedCornerShape(AdosRadius.Lg),
                    color = colors.bgRaised,
                    contentColor = colors.fgPrimary,
                    shadowElevation = 8.dp,
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = AdosSpacing.S1)) {
                        items.forEach { item ->
                            AdosListItem(
                                headline = item.label,
                                onClick = {
                                    close()
                                    item.onClick()
                                },
                                enabled = item.enabled,
                                leading = item.icon?.let { icon ->
                                    { Icon(icon, contentDescription = null, tint = colors.fgSecondary) }
                                },
                            )
                        }
                    }
                }
            }
        }
        OverlayInitialFocus(requester)
    }
}

/**
 * Places the single child under [anchor], flipped above it when it does not fit below,
 * never above [LocalOverlayTopInset] (the top bar it must leave usable).
 */
@Composable
private fun MenuPlacement(anchor: Rect?, content: @Composable () -> Unit) {
    val topInset = LocalOverlayTopInset.current
    Layout(content = content, modifier = Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = MenuMargin.roundToPx()
        val inset = topInset.roundToPx()
        val top = inset + margin
        val below = anchor?.let { (constraints.maxHeight - it.bottom - margin).roundToInt() } ?: (constraints.maxHeight - top - margin)
        val above = anchor?.let { (it.top - margin - inset).roundToInt() } ?: 0
        val flip = anchor != null && below < constraints.maxHeight / 3 && above > below
        val maxHeight = (if (flip) above else below).coerceAtLeast(0)
        val placeable = measurables.single().measure(
            Constraints(maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0), maxHeight = maxHeight),
        )
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = (anchor?.left?.roundToInt() ?: margin)
                .coerceIn(margin, (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin))
            val y = when {
                anchor == null -> top
                flip -> (anchor.top.roundToInt() - placeable.height).coerceAtLeast(top)
                else -> anchor.bottom.roundToInt()
            }
            placeable.place(x, y)
        }
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun AdosMenuPreview() = ConsolePreview {
    val state = remember { AdosMenuState().apply { expanded = true } }
    Box(Modifier.padding(AdosSpacing.S6)) {
        AdosButton("Actions", onClick = { state.expanded = true }, modifier = Modifier.adosMenuAnchor(state))
        AdosMenu(
            state,
            listOf(
                AdosMenuItem("Rename", Icons.Outlined.Edit) {},
                AdosMenuItem("Delete", Icons.Outlined.Delete) {},
                AdosMenuItem("Export", enabled = false) {},
            ),
        )
    }
}
