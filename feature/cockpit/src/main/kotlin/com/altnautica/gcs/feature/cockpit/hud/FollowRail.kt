package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.TravelExplore
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** What the rail shows; every action dispatches through the cockpit's skill gates. */
internal data class RailUi(
    val followInstalled: Boolean,
    val followActive: Boolean,
    val followEnabled: Boolean,
    /** `strict` or `smart` as echoed by follow-me; null when unreported. */
    val reacquireMode: String?,
    /** What follow-me does while unsure of the lock (`follow` or `hold`) as echoed; null when unreported. */
    val uncertainMode: String? = null,
    /** Predictive search for this flight as echoed; null when follow-me does not report it. */
    val predictiveSearch: Boolean? = null,
    /** Written follow-me settings not yet echoed, by config key. */
    val pending: Map<String, JsonPrimitive> = emptyMap(),
    /** Main and other leg labels when a down-looking leg can be swapped in. */
    val swapLabels: Pair<String, String>?,
    val canRecord: Boolean,
    val recording: Boolean,
)

/**
 * Following-mode right rail: FOLLOW / TRACKING ×, the STRICT | SMART re-acquire toggle, when
 * unsure (FOLLOW | HOLD) and the predictive search switch, the camera swap and REC. On a short
 * phone the two follow settings fold behind More with the camera swap and REC. Hold, return
 * and land stay on the left column only.
 */
@Composable
internal fun FollowRail(
    ui: RailUi,
    width: WidthClass,
    safeHeight: Dp,
    onFollow: () -> Unit,
    /** Writes follow-me setting `key` (`reacquire_mode`, `uncertain_mode`, `predictive_search`). */
    onSetting: (String, JsonPrimitive) -> Unit,
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
                ReacquireToggle(ui.reacquireMode, ui.pending[CockpitAutonomy.REACQUIRE_KEY]?.contentOrNull, size) {
                    onSetting(CockpitAutonomy.REACQUIRE_KEY, JsonPrimitive(it))
                }
            }
            val showExtras = !fold || unfolded
            if (fold && !unfolded) RailButton(Icons.Outlined.MoreHoriz, specText("follow.more"), size, chrome.text, true) { unfolded = true }
            if (showExtras) {
                if (ui.followInstalled) FollowSettings(ui, size, onSetting)
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

/** When unsure (FOLLOW | HOLD) and the predictive search switch, each shown once follow-me reports it. */
@Composable
private fun FollowSettings(ui: RailUi, size: Dp, onSetting: (String, JsonPrimitive) -> Unit) {
    ui.uncertainMode?.let { mode ->
        SegmentedToggle(
            description = specText("follow.setting.uncertain_mode"),
            tag = "uncertain",
            options = listOf("follow" to specText("follow.chip"), "hold" to specText("follow.phase.holding")),
            value = mode, pending = ui.pending[CockpitAutonomy.UNCERTAIN_KEY]?.contentOrNull, size = size,
        ) { onSetting(CockpitAutonomy.UNCERTAIN_KEY, JsonPrimitive(it)) }
    }
    ui.predictiveSearch?.let { on ->
        PredictiveToggle(on, ui.pending[CockpitAutonomy.PREDICTIVE_KEY]?.booleanOrNull, size) {
            onSetting(CockpitAutonomy.PREDICTIVE_KEY, JsonPrimitive(it))
        }
    }
}

/** The predictive search switch for this flight; a pending value is outlined dashed until echoed. */
@Composable
private fun PredictiveToggle(on: Boolean, pending: Boolean?, size: Dp, onToggle: (Boolean) -> Unit) {
    val c = AdosTheme.colors
    val chrome = LocalChrome.current
    val shown = pending ?: on
    val pendingWord = specText("setup.state.pending").lowercase()
    val tint = if (shown) chrome.text else chrome.textDim
    Box(
        Modifier
            .testTag("predictive-search")
            .size(size)
            .drawBehind {
                val r = CornerRadius(chrome.radiusSmall.toPx())
                if (shown && pending == null) drawRoundRect(c.hudPrimary.copy(alpha = 0.35f), cornerRadius = r)
                if (pending != null) {
                    drawRoundRect(chrome.text, cornerRadius = r, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                }
            }
            .semantics { if (pending != null) stateDescription = pendingWord }
            .toggleable(value = shown, role = Role.Switch, onValueChange = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (shown) Icons.Outlined.TravelExplore else Icons.Outlined.SearchOff, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(
                specText("follow.setting.predictive_search"),
                style = chrome.labelText.copy(fontSize = 8.sp, lineHeight = 9.sp, fontWeight = FontWeight.SemiBold),
                color = tint, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

/** STRICT | SMART segmented control; a pending choice is outlined dashed until echoed. */
@Composable
internal fun ReacquireToggle(mode: String?, pending: String?, size: Dp, onSelect: (String) -> Unit) = SegmentedToggle(
    description = specText("target.reacquire_mode"),
    tag = "reacquire",
    options = listOf("strict" to specText("follow.mode.strict"), "smart" to specText("follow.mode.smart")),
    value = mode, pending = pending, size = size, onSelect = onSelect,
)

/** A two-value follow-me setting as a segmented control; a pending choice is outlined dashed until echoed. */
@Composable
private fun SegmentedToggle(
    description: String,
    tag: String,
    options: List<Pair<String, String>>,
    value: String?,
    pending: String?,
    size: Dp,
    onSelect: (String) -> Unit,
) {
    val c = AdosTheme.colors
    val chrome = LocalChrome.current
    val pendingWord = specText("setup.state.pending").lowercase()
    Column(Modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((option, label) in options) {
            val active = (pending ?: value) == option
            val isPending = pending == option
            Box(
                Modifier
                    .testTag("$tag-$option")
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
                    .clickable(role = Role.RadioButton) { if ((pending ?: value) != option) onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = railText(), color = if (active) chrome.text else chrome.textDim, modifier = Modifier.padding(horizontal = AdosSpacing.S1))
            }
        }
    }
}
