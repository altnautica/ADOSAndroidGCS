package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.feature.cockpit.flow.specText

/**
 * The alert list under the pill: every active item with its severity icon (✕ critical,
 * ⚠ warning, ℹ info), its text, and its source under the text. Opening it acknowledges
 * what it shows; a tap outside it or system Back closes it. Nothing above [topInset]
 * (the top bar) is covered, so STOP takes the first press.
 */
@Composable
fun StatusList(items: List<StatusItem>, onClose: () -> Unit, modifier: Modifier = Modifier, topInset: Dp = 0.dp) {
    val c = LocalChrome.current
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = onClose,
        )
    }
    Box(Modifier.fillMaxSize().padding(top = topInset).clickable(interactionSource = null, indication = null, onClick = onClose)) {
        ChromePanel(modifier.widthIn(min = 280.dp, max = 420.dp).testTag("status-list")) {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(c.pad)) {
                Text(specText("cockpit.alerts.title"), style = c.chipText)
                Spacer(Modifier.height(6.dp))
                if (items.isEmpty()) {
                    Text(specText("cockpit.alerts.empty"), style = c.topBarText.copy(color = c.textDim))
                }
                for (item in items) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            severityIcon(item.severity),
                            contentDescription = null,
                            tint = severityColor(item.severity),
                            modifier = Modifier.size(c.icon).testTag("status-icon-${item.severity.name.lowercase()}"),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(statusItemTitle(item), style = c.topBarText, maxLines = 2)
                            Text(specText("cockpit.status.source.${item.source.key}"), style = c.labelText)
                        }
                    }
                }
            }
        }
    }
}

/** The list's severity icon: ✕ for critical, ⚠ for a warning, ℹ for information. */
internal fun severityIcon(s: StatusSeverity): ImageVector = when (s) {
    StatusSeverity.Critical -> Icons.Outlined.Close
    StatusSeverity.Warning -> Icons.Outlined.WarningAmber
    StatusSeverity.Ok -> Icons.Outlined.Info
}
