package com.altnautica.gcs.feature.cockpit.skillbar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import com.altnautica.gcs.feature.cockpit.chrome.rememberRedrawBeat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.console.ConsoleButton
import com.altnautica.gcs.core.designsystem.component.console.ControllerGlyph
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.feature.cockpit.SlotUi
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.specText

/** The picker's actions on a skill id. */
interface SkillPickerActions {
    fun onTab(tab: PickerTab)
    fun onSelect(skillId: String)
    fun onGo(skillId: String)
    fun onStop(skillId: String)
    fun onDismiss()
}

/**
 * The bottom-right chip: the active skill and its state (pending ring, ACTIVE (stale),
 * last seen), or "Skills" when none runs. Opens the picker.
 */
@Composable
fun SkillPickerChip(chip: PickerChip, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    val skill = when (chip) {
        PickerChip.Idle -> null
        is PickerChip.Pending -> chip.skill
        is PickerChip.Active -> chip.skill
        is PickerChip.ActiveStale -> chip.skill
        is PickerChip.LastSeen -> chip.skill
    }
    val label = skill?.let { skillLabel(it) } ?: specText("skill.picker.title")
    val detail = when (chip) {
        is PickerChip.Active -> chip.badge
        is PickerChip.ActiveStale -> specText("skill.active_stale")
        is PickerChip.LastSeen -> specText("skill.last_seen", "n" to chip.seconds.toString())
        else -> null
    }
    val edge = when (chip) {
        is PickerChip.Active -> colors.hudGood
        is PickerChip.ActiveStale -> colors.hudWarn
        else -> null
    }
    val openLabel = specText("skill.picker.open")
    val chrome = LocalChrome.current
    val shape = RoundedCornerShape(chrome.radiusSmall)
    ChromePanel(
        modifier
            .testTag(PICKER_CHIP_TAG)
            .defaultMinSize(minHeight = AdosTheme.touchMin, minWidth = 96.dp)
            .graphicsLayer {
                alpha = if (chip is PickerChip.LastSeen) 0.55f else 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // A running skill's edge in its state hue, over the chrome hairline.
            .then(edge?.let { Modifier.border(if (chip is PickerChip.Active) 2.dp else 1.dp, it, shape) } ?: Modifier)
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen),
        shape = shape,
    ) {
        Row(
            Modifier.align(Alignment.CenterStart).padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            if (chip is PickerChip.Pending) {
                PendingRing(colors.hudPrimary, Modifier.size(AdosSize.IconMd))
            } else {
                Icon(skill?.let { skillIcon(it.icon) } ?: Icons.Outlined.AutoAwesome, contentDescription = null, tint = colors.hudPrimary, modifier = Modifier.size(AdosSize.IconMd))
            }
            Column {
                Text(label, style = chrome.chipText, color = chrome.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let { Text(it, style = chrome.labelText, color = chrome.textDim, maxLines = 1) }
            }
        }
    }
}

/**
 * The pending ring: a quarter arc that turns a tenth of a turn on each flight-screen beat.
 * The step is read by the draw pass only, so a pending skill redraws the ring (never
 * recomposes it) at most 10 times a second, in the beat's frame, instead of every frame.
 */
@Composable
internal fun PendingRing(color: Color, modifier: Modifier = Modifier) {
    val step = remember { mutableIntStateOf(0) }
    val beat = rememberRedrawBeat()
    LaunchedEffect(beat) { beat.ticks.collect { step.intValue = (step.intValue + 1) % PENDING_STEPS } }
    Spacer(
        modifier.drawWithCache {
            val w = 2.dp.toPx()
            val stroke = Stroke(w)
            onDrawBehind {
                drawArc(color, step.intValue * 360f / PENDING_STEPS - 90f, 90f, false, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = stroke)
            }
        },
    )
}

private const val PENDING_STEPS = 10

/**
 * The skill picker: tabs Behaviors / Camera / Navigation / Utility, each plugin's skills under
 * its name. Selecting a skill opens its Tweak panel ([tweak]) with GO and Stop; a camera
 * one-shot fires on tap. Drawn in the cockpit window; place it in a full-size box.
 */
@Composable
fun SkillPickerSheet(ui: SkillPickerUi, actions: SkillPickerActions, tweak: @Composable (Skill) -> Unit) {
    val colors = AdosTheme.colors
    AdosSheet(actions::onDismiss, title = specText("skill.picker.title")) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = AdosSpacing.S4),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
        ) {
            PickerTab.entries.forEach { tab -> TabButton(tab, tab == ui.tab, tab in ui.populated) { actions.onTab(tab) } }
        }
        Column(
            Modifier.fillMaxWidth().padding(AdosSpacing.S4),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            if (ui.groups.isEmpty()) {
                Text(specText("skill.picker.empty"), style = MaterialTheme.typography.bodyMedium, color = colors.fgSecondary)
            }
            ui.groups.forEach { group ->
                Text(group.pluginName, style = MaterialTheme.typography.titleSmall, color = colors.fgSecondary, modifier = Modifier.testTag("$PICKER_GROUP_TAG:${group.pluginId}"))
                group.skills.forEach { slot ->
                    val skill = slot.skill ?: return@forEach
                    SkillRow(slot, selected = skill.id == ui.selectedId, actions = actions, tweak = tweak)
                }
            }
        }
    }
}

/** A tab; a tab with no skills is drawn dimmed and takes no tap or focus, so a press never seems to do nothing. */
@Composable
private fun TabButton(tab: PickerTab, selected: Boolean, populated: Boolean, onClick: () -> Unit) {
    val colors = AdosTheme.colors
    Surface(
        modifier = Modifier
            .defaultMinSize(minHeight = AdosTheme.touchMin, minWidth = 88.dp)
            .testTag("$PICKER_TAB_TAG:${tab.name}")
            .semantics { this.selected = selected }
            .clickable(enabled = populated || selected, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(AdosRadius.Lg),
        color = if (selected) colors.accentPrimary.copy(alpha = 0.16f) else colors.bgRaised,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.accentPrimary else colors.borderDefault),
    ) {
        Text(
            specText(tabKey(tab)),
            style = MaterialTheme.typography.labelLarge,
            color = if (populated) colors.fgPrimary else colors.fgSecondary,
            modifier = Modifier.padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S3),
        )
    }
}

/** Spec string key of a tab's label. */
fun tabKey(tab: PickerTab): String = "skill.picker.tab." + when (tab) {
    PickerTab.Behaviors -> "behavior"
    PickerTab.Camera -> "camera"
    PickerTab.Navigation -> "navigation"
    PickerTab.Utility -> "utility"
}

/** A camera one-shot fires on tap; every other skill opens its Tweak panel first. */
fun firesDirectly(skill: Skill): Boolean = skill.oneShot && skill.category == SkillCategory.Camera

@Composable
private fun SkillRow(slot: SlotUi, selected: Boolean, actions: SkillPickerActions, tweak: @Composable (Skill) -> Unit) {
    val skill = slot.skill ?: return
    val state = slot.state
    val colors = AdosTheme.colors
    val label = skillLabel(skill)
    val status = stateText(state)
    val direct = firesDirectly(skill)
    Column(verticalArrangement = Arrangement.spacedBy(AdosSpacing.S2)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AdosTheme.touchMin)
                .testTag("$PICKER_SKILL_TAG:${skill.id}")
                .graphicsLayer {
                    alpha = if (state?.lastSeenS != null || state?.enabled == false && state.active.not()) 0.55f else 1f
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
                .semantics {
                    this.selected = selected
                    if (status != null) stateDescription = status
                }
                .clickable(role = Role.Button) { if (direct) actions.onGo(skill.id) else actions.onSelect(skill.id) },
            shape = RoundedCornerShape(AdosRadius.Lg),
            color = if (selected) colors.accentPrimary.copy(alpha = 0.12f) else colors.bgRaised,
            border = BorderStroke(if (state?.active == true) 2.dp else 1.dp, if (state?.active == true) colors.statusSuccess else colors.borderDefault),
        ) {
            Row(Modifier.padding(horizontal = AdosSpacing.S3, vertical = AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
                if (state?.pending == true) {
                    PendingRing(MaterialTheme.colorScheme.primary, Modifier.size(AdosSize.IconMd))
                } else {
                    Icon(skillIcon(skill.icon), contentDescription = null, tint = colors.accentPrimary, modifier = Modifier.size(AdosSize.IconMd))
                }
                Spacer(Modifier.width(AdosSpacing.S3))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.fgSecondary, maxLines = 2) }
                }
                state?.badge?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.fgSecondary) }
            }
        }
        if (selected && !direct) {
            tweak(skill)
            GoStop(skill, state, actions)
        }
    }
}

@Composable
private fun GoStop(skill: Skill, state: SkillState?, actions: SkillPickerActions) {
    val controller = LocalControllerUi.current
    val running = state?.active == true || state?.stale == true
    Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalAlignment = Alignment.CenterVertically) {
        if (controller.active) ControllerGlyph(ConsoleButton.Confirm)
        AdosButton(
            specText("skill.go"),
            onClick = { actions.onGo(skill.id) },
            enabled = state?.enabled == true && !running && state.pending.not(),
            modifier = Modifier.weight(1f).testTag("$PICKER_GO_TAG:${skill.id}"),
        )
        if (controller.active) ControllerGlyph(ConsoleButton.Back)
        AdosButton(
            specText("skill.stop"),
            onClick = { actions.onStop(skill.id) },
            // Stop never disables while the skill runs, even on a stale link: it is queued.
            enabled = running && state?.stopQueued != true,
            variant = AdosButtonVariant.Secondary,
            modifier = Modifier.weight(1f).testTag("$PICKER_STOP_TAG:${skill.id}"),
        )
    }
}

/** The row's state line: stale, last seen, queued stop, active, or the disabled reason. */
@Composable
private fun stateText(state: SkillState?): String? = when {
    state == null -> null
    state.stopQueued -> specText("skill.stop_queued")
    state.stale -> specText("skill.active_stale")
    state.lastSeenS != null -> specText("skill.last_seen", "n" to state.lastSeenS.toString())
    state.active -> specText("skill.active")
    !state.enabled -> state.reasonKey?.let { reasonText(it) }
    else -> null
}

const val PICKER_CHIP_TAG = "skill_picker_chip"
const val PICKER_TAB_TAG = "skill_picker_tab"
const val PICKER_GROUP_TAG = "skill_picker_group"
const val PICKER_SKILL_TAG = "skill_picker_skill"
const val PICKER_GO_TAG = "skill_picker_go"
const val PICKER_STOP_TAG = "skill_picker_stop"
