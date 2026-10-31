package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.R
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing

private val HeaderHeight = 48.dp

/**
 * Frame of a full-screen console surface: a 48 dp header (back, title, tabs,
 * actions), the content, and the controller [hints] at the bottom. Tabs sit inline
 * after the title in landscape and in a row under the header in portrait; L1/R1
 * (or the generic bumpers) cycle them, looping. [content] receives the padding
 * that keeps it clear of the header, the hint bar and the system bars.
 */
@Composable
fun ConsoleScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tabs: List<String> = emptyList(),
    selectedTab: Int = 0,
    onSelectTab: (Int) -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    hints: List<Hint> = emptyList(),
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = AdosTheme.colors
    val landscape = isLandscape()
    ConsoleCommandHandler(enabled = tabs.size > 1) { command ->
        when (command) {
            ConsoleCommand.PrevTab -> {
                onSelectTab((selectedTab - 1).mod(tabs.size))
                true
            }
            ConsoleCommand.NextTab -> {
                onSelectTab((selectedTab + 1).mod(tabs.size))
                true
            }
            else -> false
        }
    }
    Scaffold(
        modifier = modifier,
        containerColor = colors.bgCanvas,
        contentColor = colors.fgPrimary,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                Row(
                    Modifier.fillMaxWidth().height(HeaderHeight).padding(horizontal = AdosSpacing.S2),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
                ) {
                    if (onBack != null) BackButton(onBack)
                    Text(
                        title,
                        modifier = Modifier.padding(start = if (onBack == null) AdosSpacing.S2 else 0.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.fgPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (landscape && tabs.isNotEmpty()) {
                        TabPills(tabs, selectedTab, onSelectTab, Modifier.weight(1f))
                    } else {
                        Box(Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, content = actions)
                }
                if (!landscape && tabs.isNotEmpty()) {
                    TabPills(tabs, selectedTab, onSelectTab, Modifier.fillMaxWidth().padding(horizontal = AdosSpacing.S2))
                }
            }
        },
        bottomBar = {
            HintBar(hints, Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)))
        },
        content = content,
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    val controller = LocalControllerUi.current.active
    Box {
        IconButton(onClick = onBack, modifier = Modifier.consoleFocus(CircleShape)) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(R.string.console_back),
                tint = AdosTheme.colors.fgPrimary,
            )
        }
        if (controller) {
            ControllerGlyph(ConsoleButton.Back, Modifier.align(Alignment.BottomEnd), size = 14.dp)
        }
    }
}

@Composable
private fun TabPills(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val colors = AdosTheme.colors
    val controller = LocalControllerUi.current.active && tabs.size > 1
    val shape = RoundedCornerShape(AdosRadius.Xl)
    Row(
        modifier.horizontalScroll(rememberScrollState()).heightIn(min = HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S1),
    ) {
        if (controller) ControllerGlyph(ConsoleButton.PrevTab)
        tabs.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                Modifier
                    .consoleFocus(shape)
                    .clip(shape)
                    .background(if (isSelected) colors.accentPrimary.copy(alpha = 0.12f) else colors.bgCanvas)
                    .semantics { this.selected = isSelected }
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .heightIn(min = 36.dp)
                    .padding(horizontal = AdosSpacing.S3),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.accentPrimary else colors.fgSecondary,
                    maxLines = 1,
                )
            }
        }
        if (controller) ControllerGlyph(ConsoleButton.NextTab)
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun ConsoleScaffoldPreview() = ConsolePreview {
    ConsoleScaffold(
        title = "Parameters",
        onBack = {},
        tabs = listOf("All", "Modified", "Advanced"),
        actions = {
            IconButton(onClick = {}) { Icon(Icons.Outlined.Refresh, contentDescription = null) }
        },
        hints = listOf(Hint(ConsoleButton.Confirm, "Edit"), Hint(ConsoleButton.Back, "Back")),
    ) { padding ->
        Column(Modifier.padding(padding)) {
            AdosListItem("ARMING_CHECK", onClick = {}, supporting = "1")
            AdosListItem("BATT_CAPACITY", onClick = {}, supporting = "5200", selected = true)
        }
    }
}

@Preview(widthDp = 360, heightDp = 720)
@Composable
private fun ConsoleScaffoldPortraitPreview() = ConsolePreview {
    ConsoleScaffold(title = "Logs", onBack = {}, tabs = listOf("Flights", "Dataflash")) { padding ->
        Box(Modifier.padding(padding).size(1.dp))
    }
}
