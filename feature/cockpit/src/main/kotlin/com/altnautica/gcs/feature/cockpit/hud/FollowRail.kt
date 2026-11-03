package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PersonPinCircle
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.AdosTypography
import com.altnautica.gcs.core.designsystem.theme.GLOVE_TOUCH_SCALE
import com.altnautica.gcs.core.designsystem.tokens.AdosRadius
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** What the rail shows; every action dispatches through the same gates the Skill Bar uses. */
internal data class RailUi(
    val followInstalled: Boolean,
    val followActive: Boolean,
    val followEnabled: Boolean,
    /** `strict` or `smart` as echoed by follow-me; null when unreported. */
    val reacquireMode: String?,
    /** A requested mode not yet echoed. */
    val pendingMode: String?,
    /** Main and other leg labels when a down-looking leg can be swapped in. */
    val swapLabels: Pair<String, String>?,
    val canRecord: Boolean,
    val recording: Boolean,
    /** Enabled state of the mirrored Skill Bar skills (`pause`, `rth`, `land`). */
    val skillEnabled: Map<String, Boolean>,
)

/**
 * Following-mode right rail: FOLLOW / TRACKING ×, the STRICT | SMART re-acquire toggle,
 * the camera swap and REC, plus HOLD, RTL and LAND (mirrors of the Skill Bar skills, so
 * they share its gate and confirm) at the expanded width.
 */
@Composable
internal fun FollowRail(
    ui: RailUi,
    width: WidthClass,
    safeHeight: Dp,
    onFollow: () -> Unit,
    onReacquireMode: (String) -> Unit,
    onSwap: () -> Unit,
    onRecord: () -> Unit,
    onSkill: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = AdosTheme.colors
    val base = if (width == WidthClass.Expanded) 56.dp else 48.dp
    val size = if (AdosTheme.gloveMode) base * GLOVE_TOUCH_SCALE else base
    val fold = width == WidthClass.Compact && safeHeight < 360.dp
    var unfolded by remember { mutableStateOf(false) }
    Surface(modifier, shape = RoundedCornerShape(AdosRadius.Xl), color = c.hudGlass, border = BorderStroke(1.dp, c.hudHair)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ui.followInstalled) {
                if (ui.followActive) {
                    RailButton(Icons.Outlined.Close, stringResource(R.string.hud_rail_tracking) + " ×", size, c.hudTrack, true, onFollow)
                } else {
                    RailButton(Icons.Outlined.PersonPinCircle, stringResource(R.string.hud_rail_follow), size, c.hudInk, ui.followEnabled, onFollow)
                }
                ReacquireToggle(ui.reacquireMode, ui.pendingMode, size, onReacquireMode)
            }
            val showExtras = !fold || unfolded
            if (fold && !unfolded) RailButton(Icons.Outlined.MoreHoriz, stringResource(R.string.hud_rail_more), size, c.hudInk, true) { unfolded = true }
            if (showExtras) {
                ui.swapLabels?.let { (a, b) -> RailButton(Icons.Outlined.SwapVert, stringResource(R.string.hud_rail_cam_swap, a, b), size, c.hudInk, true, onSwap) }
                if (ui.canRecord) {
                    RailButton(Icons.Outlined.FiberManualRecord, stringResource(R.string.hud_rail_rec), size, if (ui.recording) c.hudCrit else c.hudInk, true, onRecord)
                }
            }
            if (width == WidthClass.Expanded) {
                Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(c.hudHair) })
                RailButton(Icons.Outlined.Pause, stringResource(R.string.hud_rail_hold), size, c.hudInk, ui.skillEnabled["pause"] == true) { onSkill("pause") }
                RailButton(Icons.Outlined.Home, stringResource(R.string.hud_rail_rtl), size, c.hudInk, ui.skillEnabled["rth"] == true) { onSkill("rth") }
                RailButton(Icons.Outlined.FlightLand, stringResource(R.string.hud_rail_land), size, c.hudInk, ui.skillEnabled["land"] == true) { onSkill("land") }
            }
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, size: Dp, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    val c = AdosTheme.colors
    Surface(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(AdosRadius.Lg),
        color = c.hudGlassStrong,
        contentColor = tint,
        border = BorderStroke(1.dp, c.hudHair),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, style = railText(), maxLines = 1)
        }
    }
}

@Composable
private fun railText() = AdosTypography.numeric.small.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold)

/** STRICT | SMART segmented control; a pending choice is outlined dashed until echoed. */
@Composable
internal fun ReacquireToggle(mode: String?, pending: String?, size: Dp, onSelect: (String) -> Unit) {
    val c = AdosTheme.colors
    val description = stringResource(R.string.hud_rail_reacquire_description)
    val pendingWord = stringResource(R.string.hud_rail_pending)
    Column(Modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((value, label) in listOf("strict" to stringResource(R.string.hud_rail_strict), "smart" to stringResource(R.string.hud_rail_smart))) {
            val active = (pending ?: mode) == value
            val isPending = pending == value
            Box(
                Modifier
                    .size(width = size, height = size / 2)
                    .drawBehind {
                        val r = CornerRadius(6.dp.toPx())
                        if (active && !isPending) drawRoundRect(c.hudPrimary.copy(alpha = 0.35f), cornerRadius = r)
                        if (isPending) {
                            drawRoundRect(c.hudInk, cornerRadius = r, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                        }
                    }
                    .semantics {
                        selected = active
                        if (isPending) stateDescription = pendingWord
                    }
                    .clickable(role = Role.RadioButton) { if ((pending ?: mode) != value) onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = railText(), color = if (active) c.hudInk else c.hudMuted, modifier = Modifier.padding(horizontal = AdosSpacing.S1))
            }
        }
    }
}
