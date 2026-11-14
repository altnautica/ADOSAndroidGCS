package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlightLand
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.theme.GLOVE_TOUCH_SCALE
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPrompt
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPromptAction
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPrompts
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.LocalChrome
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Test tag of the waiting prompt, and of each action as `follow-prompt-<wire>`. */
internal const val FOLLOW_PROMPT_TAG = "follow-prompt"

/**
 * The waiting-for-instructions prompt: while follow-me holds at its latched point for the
 * operator, a panel under the follow strip names why and offers the actions [prompt] allows
 * (search again, pick a target, return home, land, stop following). It is not modal, so the
 * video and the columns stay live. It never takes focus by itself: focus moves in on a
 * [focusRequests] emission (the controller's select) or when the operator moves it there (Tab,
 * the arrow keys). While focus is inside, [onFocusedChange] reports it (the controller then
 * navigates the prompt), a hardware keyboard runs R, P, H, L and X (bare keys only), and Back
 * moves focus out again. Touch targets grow with the width class and glove mode.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FollowAwaitPrompt(
    prompt: FollowPrompt,
    width: WidthClass,
    onAction: (FollowPromptAction) -> Unit,
    modifier: Modifier = Modifier,
    focusRequests: Flow<Unit> = emptyFlow(),
    onFocusedChange: (Boolean) -> Unit = {},
) {
    val c = AdosTheme.colors
    val chrome = LocalChrome.current
    val base = when (width) {
        WidthClass.Compact, WidthClass.Medium -> 48.dp
        WidthClass.Expanded -> 56.dp
        WidthClass.Tv -> 64.dp
    }
    val target = if (AdosTheme.gloveMode) base * GLOVE_TOUCH_SCALE else base
    val first = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val reportFocus by rememberUpdatedState(onFocusedChange)
    LaunchedEffect(focusRequests) {
        focusRequests.collect {
            // A controller press leaves touch mode only through the UI bridge; the buttons take focus only outside it.
            inputMode.requestInputMode(InputMode.Keyboard)
            runCatching { first.requestFocus() }
        }
    }
    // A prompt that goes away with focus inside hands the controller back to flight.
    DisposableEffect(Unit) { onDispose { if (focused) reportFocus(false) } }
    if (LocalNavigationEventDispatcherOwner.current != null) {
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = focused,
            onBackCompleted = { focusManager.clearFocus() },
        )
    }
    ChromePanel(
        modifier
            .widthIn(max = 720.dp)
            .border(1.dp, c.hudCrit.copy(alpha = 0.7f), RoundedCornerShape(chrome.radius))
            .testTag(FOLLOW_PROMPT_TAG)
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .onFocusChanged { s ->
                if (s.hasFocus != focused) {
                    focused = s.hasFocus
                    reportFocus(s.hasFocus)
                }
            }
            .focusGroup()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val modified = e.isShiftPressed || e.isCtrlPressed || e.isAltPressed || e.isMetaPressed
                val action = FollowPrompts.actionForKey(e.utf16CodePoint.toChar(), modified, prompt.actions) ?: return@onPreviewKeyEvent false
                onAction(action)
                true
            },
    ) {
        Column(Modifier.padding(chrome.pad), verticalArrangement = Arrangement.spacedBy(chrome.gap)) {
            Text(specText(prompt.titleKey), style = chrome.labelText.copy(fontWeight = FontWeight.SemiBold), color = c.hudCrit)
            Text(specText(prompt.bodyKey), style = chrome.labelText, color = chrome.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(chrome.gap), verticalArrangement = Arrangement.spacedBy(chrome.gap)) {
                prompt.actions.forEachIndexed { i, a ->
                    val focus = if (i == 0) Modifier.focusRequester(first) else Modifier
                    PromptButton(ICONS.getValue(a), specText(a.labelKey), a.key.uppercaseChar(), target, a == FollowPromptAction.Stop, focus.testTag("$FOLLOW_PROMPT_TAG-${a.wire}")) {
                        onAction(a)
                    }
                }
            }
        }
    }
}

@Composable
private fun PromptButton(icon: ImageVector, label: String, key: Char, target: Dp, critical: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = AdosTheme.colors
    val chrome = LocalChrome.current
    val tint = if (critical) c.hudCrit else chrome.text
    val shape = RoundedCornerShape(chrome.radiusSmall)
    Row(
        modifier
            .sizeIn(minWidth = target * 2, minHeight = target)
            .border(1.dp, if (critical) c.hudCrit.copy(alpha = 0.6f) else c.hudHair, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = chrome.pad),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(chrome.gap, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(chrome.icon))
        Text(label, style = chrome.labelText.copy(fontWeight = FontWeight.SemiBold), color = tint)
        Text(key.toString(), style = chrome.labelText, color = chrome.textDim)
    }
}

private val ICONS: Map<FollowPromptAction, ImageVector> = mapOf(
    FollowPromptAction.Resume to Icons.Outlined.Search,
    FollowPromptAction.Redesignate to Icons.Outlined.CenterFocusStrong,
    FollowPromptAction.Rth to Icons.Outlined.Home,
    FollowPromptAction.Land to Icons.Outlined.FlightLand,
    FollowPromptAction.Stop to Icons.Outlined.Close,
)
