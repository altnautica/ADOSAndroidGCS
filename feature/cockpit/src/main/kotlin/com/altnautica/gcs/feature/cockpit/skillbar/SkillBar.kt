package com.altnautica.gcs.feature.cockpit.skillbar

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.altnautica.gcs.core.designsystem.component.InWindowSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.GLOVE_TOUCH_SCALE
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSize
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.feature.cockpit.SheetBackHandler
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.SlotUi

/** Window width class of the cockpit. */
enum class WidthClass { Compact, Medium, Expanded }

/** Slot size per width class (CS-8), scaled up in glove mode. */
@Composable
fun slotSize(width: WidthClass): Dp {
    val base = when (width) {
        WidthClass.Compact -> 64.dp
        WidthClass.Medium -> 72.dp
        WidthClass.Expanded -> 88.dp
    }
    return if (AdosTheme.gloveMode) base * GLOVE_TOUCH_SCALE else base
}

/** Slots shown on the bar before the overflow drawer takes the rest. */
fun visibleSlotCount(width: WidthClass): Int = when (width) {
    WidthClass.Compact -> 6
    WidthClass.Medium -> 10
    WidthClass.Expanded -> 12
}

/**
 * How many slots fit beside the drawer button in [availableDp], capped at [cap];
 * the drawer is never pushed off screen, so every skill stays reachable.
 */
fun fittingSlotCount(availableDp: Float, slotDp: Float, gapDp: Float, cap: Int): Int {
    val cells = ((availableDp + gapDp) / (slotDp + gapDp)).toInt()
    return (cells - 1).coerceIn(0, cap)
}

fun gamepadButtonName(button: Int): String = when (button) {
    GamepadButton.A -> "A"
    GamepadButton.B -> "B"
    GamepadButton.X -> "X"
    GamepadButton.Y -> "Y"
    GamepadButton.L1 -> "LB"
    GamepadButton.R1 -> "RB"
    GamepadButton.L2 -> "LT"
    GamepadButton.R2 -> "RT"
    GamepadButton.L3 -> "L3"
    GamepadButton.R3 -> "R3"
    else -> "B$button"
}

/** The Skill Bar (CS-5): filled slots in loadout order, then the overflow drawer button. */
@Composable
fun SkillBar(
    slots: List<SlotUi>,
    width: WidthClass,
    onActivate: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val size = slotSize(width)
    val filled = slots.filter { it.skill != null }
    BoxWithConstraints(modifier) {
        val count = fittingSlotCount(maxWidth.value, size.value, AdosSpacing.S2.value, visibleSlotCount(width))
        Row(horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2), verticalAlignment = Alignment.Bottom) {
            for (slot in filled.take(count)) SkillSlot(slot, size, onActivate)
            DrawerButton(size, onOpenDrawer)
        }
    }
}

@Composable
private fun DrawerButton(size: Dp, onClick: () -> Unit) {
    val colors = AdosTheme.colors
    val label = stringResource(R.string.cockpit_skill_drawer)
    Surface(
        modifier = Modifier
            .size(size)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(AdosRadius.Xl),
        color = colors.hudGlass,
        contentColor = colors.hudInk,
        border = BorderStroke(1.dp, colors.hudHair),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Apps, contentDescription = label, modifier = Modifier.size(AdosSize.IconLg))
        }
    }
}

@Composable
fun SkillSlot(slot: SlotUi, size: Dp, onActivate: (String) -> Unit, modifier: Modifier = Modifier) {
    val skill = slot.skill ?: return
    val state = slot.state
    val enabled = state?.enabled ?: false
    val colors = AdosTheme.colors
    val label = skillLabel(skill)
    val reason = state?.reasonKey?.takeIf { !enabled }?.let { reasonText(it) }
    val danger = skill.id == "kill" || skill.id == "abort"
    val edge = when {
        state?.active == true -> colors.hudGood
        danger -> colors.hudCrit
        else -> colors.hudHair
    }
    val hint = listOfNotNull(slot.keyChord?.uppercase(), slot.gamepadButton?.let(::gamepadButtonName)).joinToString(" · ")
    val sweep = remember(skill.id) { Animatable(0f) }
    val cooldownEnd = state?.cooldownEndsAtMs
    LaunchedEffect(cooldownEnd) {
        if (cooldownEnd == null) {
            sweep.snapTo(0f)
            return@LaunchedEffect
        }
        val remaining = (cooldownEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        sweep.snapTo(1f)
        sweep.animateTo(0f, tween(remaining.toInt(), easing = LinearEasing))
    }
    Column(modifier.width(size), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(size)
                .alpha(if (enabled) 1f else 0.5f)
                .semantics {
                    contentDescription = label
                    if (reason != null) stateDescription = reason
                }
                .clickable(role = Role.Button) { onActivate(skill.id) }
                .drawWithContent {
                    drawContent()
                    val f = sweep.value
                    if (f > 0f) drawRect(colors.mediaScrim, Offset(0f, this.size.height * (1f - f)), Size(this.size.width, this.size.height * f))
                },
            shape = RoundedCornerShape(AdosRadius.Xl),
            color = colors.hudGlass,
            contentColor = colors.hudInk,
            border = BorderStroke(if (state?.active == true || danger) 2.dp else 1.dp, edge),
        ) {
            Column(
                Modifier.fillMaxSize().padding(AdosSpacing.S1),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(skillIcon(skill.icon), contentDescription = null, tint = if (danger) colors.hudCrit else colors.hudPrimary, modifier = Modifier.size(AdosSize.IconLg))
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (hint.isNotEmpty()) {
                    Text(hint, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = colors.hudMuted, maxLines = 1)
                }
                state?.charges?.let { Text("×$it", style = MaterialTheme.typography.labelSmall, color = colors.hudInk2) }
            }
        }
        if (reason != null) {
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = colors.hudInk2,
                maxLines = 2,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The flight-mode picker entry and every bar-eligible skill (the overflow drawer, also
 * opened by Select and Ctrl+K). Drawn in the cockpit window; place it in a full-size box.
 */
@Composable
fun SkillDrawer(skills: List<SlotUi>, width: WidthClass, onActivate: (String) -> Unit, onDismiss: () -> Unit) {
    val size = slotSize(width)
    SheetBackHandler(onDismiss)
    InWindowSheet(onDismiss) {
        Text(
            stringResource(R.string.cockpit_skill_drawer),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = AdosSpacing.S6),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(size + AdosSpacing.S2),
            modifier = Modifier.fillMaxWidth().padding(AdosSpacing.S4),
            horizontalArrangement = Arrangement.spacedBy(AdosSpacing.S2),
            verticalArrangement = Arrangement.spacedBy(AdosSpacing.S3),
        ) {
            items(skills, key = { it.skill!!.id }) { slot ->
                SkillSlot(slot, size, { id -> onDismiss(); onActivate(id) })
            }
        }
    }
}
