package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.model.ParamDiff
import com.altnautica.gcs.core.model.ReadinessFix
import com.altnautica.gcs.core.skills.hud.AlertGroup
import com.altnautica.gcs.feature.cockpit.R
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
)

/**
 * The alert list under the pill: a header with the counts by severity, then one collapsible
 * section per group (`alert-groups.json`), each row with its severity icon (✕ critical,
 * ⚠ warning, ℹ info), its text, its source, its parameter rows (name, current → wanted) and at
 * most two actions: the fix first, then Hide (a warning, until it clears) or Collapse (a
 * critical, which only folds). [blockingOnly] shows only what blocks take-off, with a link to
 * every warning (the flight-state chip's sheet). A tap outside it or system Back closes it.
 * Nothing above [topInset] (the top bar) is covered, so STOP takes the first press.
 */
@Composable
fun StatusList(
    list: StatusListUi,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    blockingOnly: Boolean = false,
    actions: StatusListActions = StatusListActions(),
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
        ChromePanel(modifier.widthIn(min = 300.dp, max = 460.dp).testTag("status-list")) {
            Column(
                Modifier.heightIn(max = maxH).verticalScroll(rememberScrollState())
                    // Taps inside the panel stay inside it.
                    .clickable(interactionSource = null, indication = null) {}
                    .padding(c.pad),
            ) {
                if (blockingOnly) BlockingSheet(list, actions) else FullList(list, actions)
            }
        }
    }
}

@Composable
private fun BlockingSheet(list: StatusListUi, actions: StatusListActions) {
    val c = LocalChrome.current
    val blocking = list.blocking
    Text(
        stringResource(if (blocking.isEmpty()) R.string.alerts_chip_no_blocking else R.string.alerts_chip_blocking),
        style = c.chipText,
    )
    Spacer(Modifier.height(6.dp))
    for (row in blocking) AlertRow(row, actions)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = actions.onShowAll, modifier = Modifier.heightIn(min = 48.dp).testTag("status-show-warnings")) {
            Text(stringResource(R.string.alerts_chip_warnings, list.warnings), style = c.labelText.copy(color = c.text))
        }
        actions.onModes?.let {
            TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.alerts_chip_mode), style = c.labelText.copy(color = c.text))
            }
        }
    }
}

@Composable
private fun FullList(list: StatusListUi, actions: StatusListActions) {
    val c = LocalChrome.current
    var showHidden by remember { mutableStateOf(false) }
    // Sections holding a critical start open; the operator's toggles win after that.
    val open = remember { mutableStateMapOf<AlertGroup, Boolean>() }
    val blocking = list.blocking.size
    val warnings = list.warnings
    Text(specText("cockpit.alerts.title"), style = c.chipText)
    if (blocking + warnings > 0) {
        val counts = listOfNotNull(
            pluralStringResource(R.plurals.alerts_blocking, blocking, blocking).takeIf { blocking > 0 },
            pluralStringResource(R.plurals.alerts_warnings, warnings, warnings).takeIf { warnings > 0 },
        ).joinToString(" · ")
        Text(counts, style = c.labelText, modifier = Modifier.testTag("status-counts"))
    }
    if (list.hidden.isNotEmpty()) {
        TextButton(onClick = { showHidden = !showHidden }, modifier = Modifier.heightIn(min = 48.dp).testTag("status-show-hidden")) {
            Text(
                stringResource(if (showHidden) R.string.alerts_hide_hidden else R.string.alerts_show_hidden, list.hidden.size),
                style = c.labelText.copy(color = c.text),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    if (list.sections.isEmpty()) {
        Text(stringResource(R.string.alerts_all_clear), style = c.topBarText.copy(color = c.textDim))
    }
    for (section in list.sections) {
        val expanded = open[section.group] ?: section.rows.any { it.severity == StatusSeverity.Critical }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button) { open[section.group] = !expanded }
                .testTag("status-section-${section.group.wire}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "▾" else "▸", style = c.topBarText, modifier = Modifier.width(20.dp))
            Text(section.title, style = c.chipText, modifier = Modifier.weight(1f))
            Text(section.rows.size.toString(), style = c.labelText)
            if (section.hidden > 0) {
                Text(" · " + pluralStringResource(R.plurals.alerts_hidden_note, section.hidden, section.hidden), style = c.labelText)
            }
        }
        if (expanded) for (row in section.rows) AlertRow(row, actions)
    }
    if (showHidden) {
        for (item in list.hidden) AlertRow(StatusRow(item, item.severity, collapsed = false), actions, hidden = true)
    }
}

/** One alert row; [hidden] marks a snoozed warning shown on request, which offers Show again. */
@Composable
private fun AlertRow(row: StatusRow, actions: StatusListActions, hidden: Boolean = false) {
    val c = LocalChrome.current
    val item = row.item
    val critical = row.severity == StatusSeverity.Critical
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("status-row-${item.key}"), verticalAlignment = Alignment.Top) {
        Text(
            severityGlyph(c, row.severity),
            style = c.topBarText.copy(color = severityColor(row.severity)),
            modifier = Modifier.width(c.icon).testTag("status-icon-${row.severity.name.lowercase()}"),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(statusItemTitle(item), style = c.topBarText, maxLines = 2)
            Text(specText("cockpit.status.source.${item.source.key}"), style = c.labelText)
            val params = item.pill?.params.orEmpty()
            if (!row.collapsed && params.isNotEmpty()) ParamTable(params)
            val fix = item.pill?.fix?.takeUnless { row.collapsed }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val hint = fix != null && actions.groundStation && (fix == ReadinessFix.ApplyProfile || fix is ReadinessFix.SetupStep)
                if (fix != null && !hint) {
                    OutlinedButton(
                        onClick = { actions.onFix(fix) },
                        border = BorderStroke(1.dp, c.text.copy(alpha = 0.5f)),
                        modifier = Modifier.heightIn(min = 48.dp).testTag("status-fix-${item.key}"),
                    ) {
                        Text(fixLabel(fix), style = c.labelText.copy(color = c.text))
                    }
                }
                if (item.pill != null) {
                    val (label, on) = when {
                        hidden -> R.string.alerts_action_unhide to false
                        critical && row.collapsed -> R.string.alerts_action_expand to false
                        critical -> R.string.alerts_action_collapse to true
                        else -> R.string.alerts_action_hide to true
                    }
                    TextButton(
                        onClick = { actions.onSnooze(item.snoozeKey, on) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("status-snooze-${item.key}"),
                    ) {
                        Text(stringResource(label), style = c.labelText.copy(color = c.textDim))
                    }
                }
            }
            if (fix != null && actions.groundStation && (fix == ReadinessFix.ApplyProfile || fix is ReadinessFix.SetupStep)) {
                Text(stringResource(R.string.alerts_ground_hint), style = c.labelText, modifier = Modifier.testTag("status-ground-hint"))
            } else if (fix == ReadinessFix.ApplyProfile) {
                Text(stringResource(R.string.alerts_apply_snapshot), style = c.labelText)
            }
        }
    }
}

/** The parameter rows: name (monospace), current → wanted; past [PARAM_ROWS] behind "Show all". */
@Composable
private fun ParamTable(params: List<ParamDiff>) {
    val c = LocalChrome.current
    var all by remember { mutableStateOf(false) }
    val shown = if (all) params else params.take(PARAM_ROWS)
    Column(Modifier.padding(vertical = 4.dp).testTag("status-params")) {
        for (p in shown) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = c.labelText.copy(color = c.text, fontFamily = FontFamily.Monospace), modifier = Modifier.weight(1.4f))
                Text(p.have?.let(::paramValue) ?: stringResource(R.string.alerts_param_not_reported), style = c.labelText, modifier = Modifier.weight(1f))
                Text("→", style = c.labelText, modifier = Modifier.padding(horizontal = 4.dp))
                Text(p.want, style = c.labelText.copy(color = c.text), modifier = Modifier.weight(1f))
            }
        }
        if (params.size > PARAM_ROWS) {
            TextButton(onClick = { all = !all }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    if (all) stringResource(R.string.alerts_param_show_fewer) else stringResource(R.string.alerts_param_show_all, params.size),
                    style = c.labelText.copy(color = c.text),
                )
            }
        }
    }
}

@Composable
private fun fixLabel(fix: ReadinessFix): String = stringResource(
    when (fix) {
        ReadinessFix.ApplyProfile -> R.string.alerts_action_apply
        is ReadinessFix.SetupStep -> R.string.alerts_action_open_setup
        is ReadinessFix.Settings -> R.string.alerts_action_open_settings
        is ReadinessFix.Parameters -> R.string.alerts_action_open_parameters
    },
)

/** A parameter value as the FC holds it: whole numbers without a decimal point. */
internal fun paramValue(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

private const val PARAM_ROWS = 6

/** The list's severity glyph from the chrome tokens: ✕ critical, ⚠ warning, ℹ anything else. */
internal fun severityGlyph(c: Chrome, s: StatusSeverity): String = when (s) {
    StatusSeverity.Critical -> c.severityGlyph["critical"] ?: "✕"
    StatusSeverity.Warning -> c.severityGlyph["warning"] ?: "⚠"
    StatusSeverity.Ok -> c.severityGlyph["info"] ?: "ℹ"
}
