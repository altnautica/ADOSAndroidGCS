package com.altnautica.gcs.feature.cockpit.chrome

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.model.ParamDiff
import com.altnautica.gcs.core.model.ParamDocBook
import com.altnautica.gcs.core.model.ReadinessFix
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.alerts.ApplyPhase
import com.altnautica.gcs.feature.cockpit.alerts.ApplyProfileFix
import com.altnautica.gcs.feature.cockpit.alerts.ApplyState
import com.altnautica.gcs.feature.cockpit.flow.specText

/** Dark ink on the light filled action button. */
private val BUTTON_INK = Color(0xFF0B0D12)

/** Parameter rows shown before "Show N more". */
private const val PARAM_ROWS = 4

/** How a section body or a parameter's detail opens and closes. */
internal val ExpandEnter: EnterTransition = expandVertically(tween(200)) + fadeIn(tween(150))
internal val ExpandExit: ExitTransition = shrinkVertically(tween(160)) + fadeOut(tween(100))

/** The 180 ms turn of a disclosure chevron. */
@Composable
internal fun chevronAngle(open: Boolean, opened: Float = 0f, closed: Float = -90f): Float {
    val angle by animateFloatAsState(if (open) opened else closed, tween(180, easing = FastOutSlowInEasing), label = "chevron")
    return angle
}

/** A pressed control shrinks slightly; returns the scale to apply. */
@Composable
internal fun pressScale(interaction: MutableInteractionSource): Float {
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "press")
    return s
}

/**
 * One alert as a card: its severity mark and title, its source, a summary and its parameter rows
 * (what each setting is, current → recommended, and why ADOS wants it), then its fix (with a line
 * on what the fix does) and Hide or Collapse. [hidden] marks a snoozed warning shown on request,
 * which offers Show again.
 */
@Composable
internal fun AlertRow(
    row: StatusRow,
    actions: StatusListActions,
    apply: ApplyState,
    docs: ParamDocBook? = null,
    hidden: Boolean = false,
) {
    val c = LocalChrome.current
    val item = row.item
    val critical = row.severity == StatusSeverity.Critical
    val tone = if (row.severity == StatusSeverity.Ok) c.textDim else severityColor(row.severity)
    val fix = item.pill?.fix?.takeUnless { row.collapsed }
    val groundHint = fix != null && actions.groundStation && (fix == ReadinessFix.ApplyProfile || fix is ReadinessFix.SetupStep)
    // This row's inline apply, once started: it replaces the fix button and the parameter rows.
    val applying = (apply.phase.takeIf { apply.owner == item.snoozeKey && fix == ReadinessFix.ApplyProfile && !groundHint })
        ?.takeUnless { it == ApplyPhase.Idle }
    val params = item.pill?.params.orEmpty()
    val shape = RoundedCornerShape(c.radiusSmall)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .clip(shape)
            .background(tone.copy(alpha = 0.07f))
            .border(1.dp, tone.copy(alpha = 0.28f), shape)
            .drawBehind { drawRect(tone, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 15.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            .testTag("status-row-${item.key}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(28.dp).background(tone.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                Text(
                    severityGlyph(c, row.severity),
                    style = c.topBarText.copy(color = tone),
                    modifier = Modifier.testTag("status-icon-${row.severity.name.lowercase()}"),
                )
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                val title = statusItemTitle(item)
                // A short title (the words before the colon) still reads the whole message aloud.
                val full = item.pill?.takeIf { it.params.isNotEmpty() }?.text?.takeIf { it != title }
                Text(
                    title,
                    style = c.topBarText.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 2,
                    modifier = Modifier.testTag("status-title-${item.key}").let { m -> full?.let { m.semantics { contentDescription = it } } ?: m },
                )
                Text(specText("cockpit.status.source.${item.source.key}"), style = c.labelText)
            }
        }
        if (!row.collapsed && params.isNotEmpty() && applying == null) {
            val summary = when (row.severity) {
                StatusSeverity.Critical -> R.string.alerts_params_summary_block
                StatusSeverity.Warning -> R.string.alerts_params_summary_warn
                StatusSeverity.Ok -> null
            }
            summary?.let { Text(stringResource(it), style = c.labelText.copy(color = c.text.copy(alpha = 0.85f))) }
            ParamRows(params, docs)
        }
        val showFix = fix != null && !groundHint && applying == null
        val snooze = item.pill?.let {
            when {
                hidden -> R.string.alerts_action_unhide to false
                critical && row.collapsed -> R.string.alerts_action_expand to false
                critical -> R.string.alerts_action_collapse to true
                else -> R.string.alerts_action_hide to true
            }
        }
        if (showFix || snooze != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (showFix && fix != null) {
                    val interaction = remember { MutableInteractionSource() }
                    val s = pressScale(interaction)
                    Button(
                        onClick = { if (fix == ReadinessFix.ApplyProfile) actions.onApplyRead(item.snoozeKey) else actions.onFix(fix) },
                        colors = ButtonDefaults.buttonColors(containerColor = c.text, contentColor = BUTTON_INK),
                        shape = RoundedCornerShape(c.radiusSmall),
                        interactionSource = interaction,
                        modifier = Modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = s; scaleY = s }.testTag("status-fix-${item.key}"),
                    ) {
                        Text(fixLabel(fix, params.size), style = c.labelText.copy(color = BUTTON_INK, fontWeight = FontWeight.SemiBold))
                    }
                }
                snooze?.let { (label, on) ->
                    TextButton(
                        onClick = { actions.onSnooze(item.snoozeKey, on) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("status-snooze-${item.key}"),
                    ) {
                        Text(stringResource(label), style = c.labelText.copy(color = c.textDim))
                    }
                }
            }
            val caption = listOfNotNull(
                fix?.takeIf { showFix }?.let { stringResource(fixHelp(it)) },
                stringResource(R.string.alerts_action_hide_help).takeIf { snooze?.first == R.string.alerts_action_hide },
            ).joinToString(" ")
            if (caption.isNotEmpty()) Text(caption, style = c.labelText)
        }
        if (groundHint) {
            Text(stringResource(R.string.alerts_ground_hint), style = c.labelText, modifier = Modifier.testTag("status-ground-hint"))
        }
        applying?.let { phase ->
            ApplyPanel(
                phase,
                onWrite = actions.onApplyWrite,
                onCancel = actions.onApplyCancel,
                onOpenSetup = { actions.onFix(ReadinessFix.SetupStep(ApplyProfileFix.STEP)) },
                docs = docs,
            )
        }
    }
}

/** The parameter rows under a column header; past [PARAM_ROWS] behind "Show N more". */
@Composable
private fun ParamRows(params: List<ParamDiff>, docs: ParamDocBook?) {
    val c = LocalChrome.current
    var all by remember { mutableStateOf(false) }
    val shown = if (all) params else params.take(PARAM_ROWS)
    Column(Modifier.fillMaxWidth().testTag("status-params")) {
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Text(stringResource(R.string.alerts_param_col_setting), style = c.labelText, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.alerts_param_col_values), style = c.labelText)
        }
        for (p in shown) key(p.name) {
            val doc = remember(docs, p.name) { docs?.doc(p.name) }
            ParamRow(p.name, p.have, p.want, doc)
        }
        if (params.size > PARAM_ROWS) {
            TextButton(onClick = { all = !all }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(
                    if (all) stringResource(R.string.alerts_param_show_fewer) else stringResource(R.string.alerts_param_show_more, params.size - PARAM_ROWS),
                    style = c.labelText.copy(color = c.text),
                )
            }
        }
    }
}

@Composable
private fun fixLabel(fix: ReadinessFix, params: Int): String = when (fix) {
    ReadinessFix.ApplyProfile ->
        if (params > 0) pluralStringResource(R.plurals.alerts_action_apply_n, params, params) else stringResource(R.string.alerts_action_apply)
    is ReadinessFix.SetupStep -> stringResource(R.string.alerts_action_open_setup)
    is ReadinessFix.Settings -> stringResource(R.string.alerts_action_open_settings)
    is ReadinessFix.Parameters -> stringResource(R.string.alerts_action_open_parameters)
}

/** What the fix button does, in one line under it. */
private fun fixHelp(fix: ReadinessFix): Int = when (fix) {
    ReadinessFix.ApplyProfile -> R.string.alerts_action_apply_help
    is ReadinessFix.SetupStep -> R.string.alerts_action_setup_help
    is ReadinessFix.Settings -> R.string.alerts_action_settings_help
    is ReadinessFix.Parameters -> R.string.alerts_action_parameters_help
}
