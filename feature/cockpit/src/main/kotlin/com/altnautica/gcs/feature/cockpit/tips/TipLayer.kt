package com.altnautica.gcs.feature.cockpit.tips

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.core.skills.tips.TipDecision
import com.altnautica.gcs.core.skills.tips.TipRect
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.core.skills.tips.TipsRepository
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.specText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How often the layer re-reads the screen, ms. */
private const val TICK_MS = 250L

/** The tip's widest size and its text size. */
private val TIP_MAX_WIDTH = 260.dp
private const val TIP_TEXT_SP = 13

const val TAG_TIP = "tip"
const val TAG_TIP_DISMISS = "tip-dismiss"

/**
 * The flight screen's way to dismiss the tip on screen from outside the tip layer: a tap
 * on an anchor the layer cannot see, or a controller button. Inert until a [TipLayer] using
 * it is on screen.
 */
@Stable
class TipsHandle internal constructor() {
    private var controller: TipsController? = null
    private var scope: CoroutineScope? = null

    /** Whether a tip is on screen now. */
    val isShowing: Boolean get() = controller?.shown?.value != null

    /** The pilot tapped the anchor [anchorId]: dismisses the tip on screen when it points at it. */
    fun dismissFromAnchorTap(anchorId: String) {
        val c = controller ?: return
        scope?.launch { c.dismissFromAnchor(anchorId) }
    }

    /** A controller button went down: dismisses the tip on screen. Returns whether one was on screen. */
    fun dismissFromControllerButton(): Boolean {
        val c = controller ?: return false
        if (c.shown.value == null) return false
        scope?.launch { c.dismiss() }
        return true
    }

    internal fun attach(controller: TipsController, scope: CoroutineScope) {
        this.controller = controller
        this.scope = scope
    }

    internal fun detach(controller: TipsController) {
        if (this.controller !== controller) return
        this.controller = null
        scope = null
    }
}

/** A [TipsHandle] for one flight screen; pass it to its [TipLayer]. */
@Composable
fun rememberTipsHandle(): TipsHandle = remember { TipsHandle() }

/**
 * The contextual tips of the flight screen: one small chrome panel at a time beside its
 * anchor, from the cockpit spec's `tips.json` through [engine]. It fills its parent, reads
 * the anchors and never-cover zones from [LocalTipAnchors] and places the tip beside its
 * anchor clear of every never-cover zone; a tip with no clear spot is hidden unseen, as
 * when its anchor is gone. The layer itself takes no input, so touches outside the tip
 * reach the cockpit below. A tap on the tip, its button or its anchor dismisses it for
 * good; [handle] lets the flight screen do the same for a controller button.
 *
 * One composition is one flight-screen open: it counts the open on the device once.
 */
@Composable
fun TipLayer(
    facts: TipFacts,
    repository: TipsRepository,
    engine: TipsEngine,
    modifier: Modifier = Modifier,
    handle: TipsHandle = rememberTipsHandle(),
    nowMs: () -> Long = System::currentTimeMillis,
) {
    val registry = LocalTipAnchors.current
    val scope = rememberCoroutineScope()
    val controller = remember(engine, repository) {
        TipsController(engine, nowMs, repository::markSeen, repository::noteFlightScreenOpen)
    }
    val tipsState by repository.state.collectAsState(initial = null)
    val tip by controller.shown.collectAsState()

    DisposableEffect(handle, controller, registry, scope) {
        handle.attach(controller, scope)
        val onTap: (String) -> Unit = { handle.dismissFromAnchorTap(it) }
        registry?.onAnchorTap = onTap
        onDispose {
            handle.detach(controller)
            if (registry?.onAnchorTap === onTap) registry.onAnchorTap = null
        }
    }

    // Restarts on every fact or stored-state change, so arming hides the tip at once.
    LaunchedEffect(controller, facts, tipsState) {
        controller.open()
        val state = tipsState ?: return@LaunchedEffect
        val anchorIds = engine.tips.map { it.anchor }.distinct()
        while (isActive) {
            val visible = anchorIds.associateWith { registry?.isVisible(it) == true }
            val d = controller.tick(state, facts, visible)
            delay(if (d is TipDecision.Wait) (d.atMs - nowMs()).coerceIn(1L, TICK_MS) else TICK_MS)
        }
    }

    var layerBounds by remember { mutableStateOf<Rect?>(null) }
    Layout(
        content = {
            tip?.let { shown ->
                TipPanel(textKey = shown.textKey, onDismiss = { scope.launch { controller.dismiss() } })
            }
        },
        modifier = modifier.fillMaxSize().onGloballyPositioned { layerBounds = it.boundsInRoot() },
    ) { measurables, constraints ->
        val panel = measurables.firstOrNull()?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val shown = tip ?: return@layout
            val viewport = layerBounds ?: return@layout
            if (panel == null || registry == null) return@layout
            val anchor = registry.tipRect(shown.anchor) ?: return@layout
            val avoid = engine.neverCoverZones.mapNotNull { registry.tipRect(it) }
            val at = TipsEngine.placeTip(
                anchor = anchor,
                tipW = panel.width.toFloat(),
                tipH = panel.height.toFloat(),
                viewport = TipRect(viewport.left, viewport.top, viewport.width, viewport.height),
                avoid = avoid,
            )
            if (at == null) {
                // Never drawn, so never on screen: hidden unseen, as when its anchor is gone.
                scope.launch { controller.unplaced(shown.tipId) }
                return@layout
            }
            panel.place((at.x - viewport.left).roundToInt(), (at.y - viewport.top).roundToInt())
        }
    }
}

/** The tip: its text and one button, both dismissing it. */
@Composable
private fun TipPanel(textKey: String, onDismiss: () -> Unit) {
    val c = LocalChrome.current
    val text = c.chipText.copy(
        color = c.text,
        fontSize = TIP_TEXT_SP.sp,
        lineHeight = (TIP_TEXT_SP * 1.35f).sp,
        fontWeight = FontWeight.Normal,
    )
    val buttonShape = RoundedCornerShape(c.radiusSmall)
    ChromePanel(
        Modifier
            .widthIn(max = TIP_MAX_WIDTH)
            .testTag(TAG_TIP)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(specText(textKey), style = text)
            Box(
                Modifier
                    .align(Alignment.End)
                    .defaultMinSize(minHeight = 36.dp)
                    .clip(buttonShape)
                    .border(c.borderWidth, c.border, buttonShape)
                    .clickable(role = Role.Button, onClick = onDismiss)
                    .testTag(TAG_TIP_DISMISS)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(specText("tips.dismiss"), style = text.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}
