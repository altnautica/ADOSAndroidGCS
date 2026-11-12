package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PersonPinCircle
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.platform.testTag
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
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass

/** What the rail shows; every action dispatches through the cockpit's skill gates. */
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
)

/**
 * Following-mode right rail: FOLLOW / TRACKING ×, the STRICT | SMART re-acquire toggle,
 * the camera swap and REC. Hold, return and land stay on the left column only.
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
    modifier: Modifier = Modifier,
) {
    val c = AdosTheme.colors
    val chrome = LocalChrome.current
    val base = when (width) {
        WidthClass.Compact, WidthClass.Medium -> 48.dp
        WidthClass.Expanded -> 56.dp
        WidthClass.Tv -> 64.dp
    }
    val size = if (AdosTheme.gloveMode) base * GLOVE_TOUCH_SCALE else base
    val fold = width == WidthClass.Compact && safeHeight < 360.dp
    var unfolded by remember { mutableStateOf(false) }
    ChromePanel(modifier) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ui.followInstalled) {
                if (ui.followActive) {
                    RailButton(Icons.Outlined.Close, specText("follow.tracking").uppercase() + " ×", size, c.hudTrack, true, onFollow)
                } else {
                    RailButton(Icons.Outlined.PersonPinCircle, specText("follow.chip"), size, chrome.text, ui.followEnabled, onFollow)
                }
                ReacquireToggle(ui.reacquireMode, ui.pendingMode, size, onReacquireMode)
            }
            val showExtras = !fold || unfolded
            if (fold && !unfolded) RailButton(Icons.Outlined.MoreHoriz, specText("follow.more"), size, chrome.text, true) { unfolded = true }
            if (showExtras) {
                ui.swapLabels?.let { (a, b) -> RailButton(Icons.Outlined.SwapVert, specText("follow.cam_swap", "from" to a, "to" to b), size, chrome.text, true, onSwap) }
                if (ui.canRecord) {
                    RailButton(Icons.Outlined.FiberManualRecord, specText("follow.rec"), size, if (ui.recording) c.hudCrit else chrome.text, true, onRecord)
                }
            }
        }
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, size: Dp, tint: Color, enabled: Boolean, onClick: () -> Unit) {
    val chrome = LocalChrome.current
    ChromePanel(
        Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.5f)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(chrome.radiusSmall),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, style = railText(), color = tint, maxLines = 1)
        }
    }
}

@Composable
private fun railText() = AdosTypography.numeric.small.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold)

/** STRICT | SMART segmented control; a pending choice is outlined dashed until echoed. */
@Composable
internal fun ReacquireToggle(mode: String?, pending: String?, size: Dp, onSelect: (String) -> Unit) {
    val c = AdosTheme.colors
    val chrome = LocalChrome.current
    val description = specText("target.reacquire_mode")
    val pendingWord = specText("setup.state.pending").lowercase()
    Column(Modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((value, label) in listOf("strict" to specText("follow.mode.strict"), "smart" to specText("follow.mode.smart"))) {
            val active = (pending ?: mode) == value
            val isPending = pending == value
            Box(
                Modifier
                    .testTag("reacquire-$value")
                    .minimumInteractiveComponentSize()
                    .size(width = size, height = size / 2)
                    .drawBehind {
                        val r = CornerRadius(chrome.radiusSmall.toPx())
                        if (active && !isPending) drawRoundRect(c.hudPrimary.copy(alpha = 0.35f), cornerRadius = r)
                        if (isPending) {
                            drawRoundRect(chrome.text, cornerRadius = r, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                        }
                    }
                    .semantics {
                        selected = active
                        if (isPending) stateDescription = pendingWord
                    }
                    .clickable(role = Role.RadioButton) { if ((pending ?: mode) != value) onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = railText(), color = if (active) chrome.text else chrome.textDim, modifier = Modifier.padding(horizontal = AdosSpacing.S1))
            }
        }
    }
}
