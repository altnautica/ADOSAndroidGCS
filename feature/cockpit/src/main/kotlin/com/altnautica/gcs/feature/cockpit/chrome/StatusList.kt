package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.model.ParamDocBook
import com.altnautica.gcs.core.model.ReadinessFix
import com.altnautica.gcs.core.skills.hud.AlertGroup
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.alerts.ApplyState
import com.altnautica.gcs.feature.cockpit.flow.specText

/** What the alert list's rows can do. */
class StatusListActions(
    /** Follow a row's fix (Aircraft setup, settings, parameters). */
    val onFix: (ReadinessFix) -> Unit = {},
    /** Hide a warning (snooze) or fold a critical; `false` shows it again. */
    val onSnooze: (key: String, on: Boolean) -> Unit = { _, _ -> },
    /** From the blocking sheet: the full list. */
    val onShowAll: () -> Unit = {},
    /** From the blocking sheet: the Mode sheet the chip used to open. */
    val onModes: (() -> Unit)? = null,
    /** On a ground station: setup fixes run on the drone, so they read as a hint, not a button. */
    val groundStation: Boolean = false,
    /** Apply recommended values inline: read the profile step for a row (its snooze key). */
    val onApplyRead: (owner: String) -> Unit = {},
    /** Write the reviewed values (after the hold). */
    val onApplyWrite: () -> Unit = {},
    /** Leave the review; the vehicle's ground wizard is aborted. */
    val onApplyCancel: () -> Unit = {},
)

/**
 * The alert list under the pill: a header with the counts by severity, then one collapsible
 * section per group (`alert-groups.json`), each alert a card ([AlertRow]) with its severity
 * mark (✕ critical, ⚠ warning, ℹ info), its text, its source, its parameter rows (what each
 * setting is, current → recommended, why ADOS wants it) and at most two actions: the fix first,
 * then Hide (a warning, until it clears) or Collapse (a critical, which only folds).
 * [blockingOnly] shows only what blocks take-off, with a link to every warning (the
 * flight-state chip's sheet). [docs] names the flown FC's parameters; without it rows show the
 * raw names. A tap outside it or system Back closes it. Nothing above [topInset] (the top bar)
 * is covered, so STOP takes the first press.
 */
@Composable
fun StatusList(
    list: StatusListUi,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    blockingOnly: Boolean = false,
    actions: StatusListActions = StatusListActions(),
    /** The inline apply flow and the row that started it. */
    apply: ApplyState = ApplyState(),
    /** Where the panel would start (under the pill); it moves left as far as it must to stay on screen. */
    anchorX: Dp = 0.dp,
    docs: ParamDocBook? = null,
) {
    val c = LocalChrome.current
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = true,
            onBackCompleted = onClose,
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = topInset).clickable(interactionSource = null, indication = null, onClick = onClose)) {
        val maxH = maxHeight * 0.7f
        val width = minOf(PANEL_MAX_WIDTH, maxWidth - PANEL_EDGE * 2)
        val x = anchorX.coerceAtMost(maxWidth - width - PANEL_EDGE).coerceAtLeast(PANEL_EDGE)
        ChromePanel(modifier.absoluteOffset(x = x).width(width).testTag("status-list")) {
            Column(
                Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())
                    // Taps inside the panel stay inside it.
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(c.pad),
            ) {
                if (blockingOnly) BlockingSheet(list, actions, apply, docs) else FullList(list, actions, apply, docs)
            }
        }
    }
}

private val PANEL_MAX_WIDTH = 480.dp
private val PANEL_EDGE = 8.dp
private val BLOCK_GAP = 12.dp

@Composable
private fun Title(text: String) {
    val c = LocalChrome.current
    Text(text, style = c.chipText.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp))
}

/** The counts under the title: blocking in red, warnings in amber. */
@Composable
private fun Counts(blocking: Int, warnings: Int) {
    val c = LocalChrome.current
    if (blocking + warnings == 0) return
    Row(Modifier.padding(top = 6.dp).testTag("status-counts"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (blocking > 0) CountPill(pluralStringResource(R.plurals.alerts_blocking, blocking, blocking), c.critical)
        if (warnings > 0) CountPill(pluralStringResource(R.plurals.alerts_warnings, warnings, warnings), c.warning)
    }
}

@Composable
private fun CountPill(text: String, color: Color) {
    val c = LocalChrome.current
    Box(Modifier.background(color.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = c.labelText.copy(color = color))
    }
}

@Composable
private fun HeaderDivider() {
    HorizontalDivider(Modifier.padding(vertical = BLOCK_GAP), thickness = 1.dp, color = LocalChrome.current.border)
}

@Composable
private fun BlockingSheet(list: StatusListUi, actions: StatusListActions, apply: ApplyState, docs: ParamDocBook?) {
    val c = LocalChrome.current
    val blocking = list.blocking
    Title(stringResource(if (blocking.isEmpty()) R.string.alerts_chip_no_blocking else R.string.alerts_chip_blocking))
    if (blocking.isNotEmpty()) {
        Text(stringResource(R.string.alerts_blocking_subtitle), style = c.labelText, modifier = Modifier.padding(top = 2.dp))
    }
    Counts(blocking.size, list.warnings)
    HeaderDivider()
    for (row in blocking) AlertRow(row, actions, apply, docs)
    Spacer(Modifier.height(BLOCK_GAP - 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FooterLink(stringResource(R.string.alerts_chip_warnings_all, list.warnings), actions.onShowAll, Modifier.testTag("status-show-warnings"))
        actions.onModes?.let { FooterLink(stringResource(R.string.alerts_chip_mode), it) }
    }
}

/** A full-width outlined link row of the blocking sheet's footer. */
@Composable
private fun FooterLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalChrome.current
    val shape = RoundedCornerShape(c.radiusSmall)
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).border(BorderStroke(1.dp, c.border), shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = c.labelText.copy(color = c.text, fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textDim, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FullList(list: StatusListUi, actions: StatusListActions, apply: ApplyState, docs: ParamDocBook?) {
    val c = LocalChrome.current
    var showHidden by remember { mutableStateOf(false) }
    // Sections holding a critical start open; the operator's toggles win after that.
    val open = remember { mutableStateMapOf<AlertGroup, Boolean>() }
    Title(specText("cockpit.alerts.title"))
    Counts(list.blocking.size, list.warnings)
    if (list.hidden.isNotEmpty()) {
        TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.heightIn(min = 48.dp).testTag("status-show-hidden")) {
            Text(
                stringResource(if (showHidden) R.string.alerts_hide_hidden else R.string.alerts_show_hidden, list.hidden.size),
                style = c.labelText.copy(color = c.text),
            )
        }
    }
    HeaderDivider()
    if (list.sections.isEmpty()) {
        Text(stringResource(R.string.alerts_all_clear), style = c.topBarText.copy(color = c.textDim))
    }
    for (section in list.sections) {
        val expanded = open[section.group] ?: section.rows.any { it.severity == StatusSeverity.Critical }
        SectionHeader(section, expanded) { open[section.group] = !expanded }
        AnimatedVisibility(expanded, enter = ExpandEnter, exit = ExpandExit) {
            Column { for (row in section.rows) AlertRow(row, actions, apply, docs) }
        }
    }
    if (showHidden) {
        for (item in list.hidden) AlertRow(StatusRow(item, item.severity, collapsed = false), actions, apply, docs, hidden = true)
    }
}

@Composable
private fun SectionHeader(section: StatusSection, expanded: Boolean, onToggle: () -> Unit) {
    val c = LocalChrome.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .testTag("status-section-${section.group.wire}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = c.textDim, modifier = Modifier.size(20.dp).rotate(chevronAngle(expanded)))
        Text(
            section.title.uppercase(),
            style = c.labelText.copy(color = c.text, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
            modifier = Modifier.weight(1f, fill = false),
        )
        CountPill(section.rows.size.toString(), c.textDim)
        if (section.hidden > 0) {
            Text(pluralStringResource(R.plurals.alerts_hidden_note, section.hidden, section.hidden), style = c.labelText)
        }
    }
}

/** The list's severity glyph from the chrome tokens: ✕ critical, ⚠ warning, ℹ anything else. */
internal fun severityGlyph(c: Chrome, s: StatusSeverity): String = when (s) {
    StatusSeverity.Critical -> c.severityGlyph["critical"] ?: "✕"
    StatusSeverity.Warning -> c.severityGlyph["warning"] ?: "⚠"
    StatusSeverity.Ok -> c.severityGlyph["info"] ?: "ℹ"
}
