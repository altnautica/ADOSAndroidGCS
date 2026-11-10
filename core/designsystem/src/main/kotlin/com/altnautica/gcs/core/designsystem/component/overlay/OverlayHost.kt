package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.altnautica.gcs.core.designsystem.component.console.LocalControllerUi

/** One overlay drawn by the host: its content and the composition locals of the screen that opened it. */
internal class OverlayEntry(locals: CompositionLocalContext, private val content: State<@Composable () -> Unit>) {
    var locals by mutableStateOf(locals)

    @Composable
    fun Render() = content.value()
}

/**
 * Draws every open overlay (sheets, dialogs, menus) above the app content in the
 * same window, so hardware input keeps reaching the activity. Entries draw in the
 * order they opened; the newest is on top.
 */
@Stable
class OverlayHost {
    internal val entries = mutableStateListOf<OverlayEntry>()
    private var lastFocused: FocusRequester? = null
    private var restoreTarget: FocusRequester? = null

    /** Records the screen control that holds focus while no overlay is open. */
    internal fun focusedOutsideOverlay(requester: FocusRequester) {
        if (entries.isEmpty()) lastFocused = requester
    }

    /** Opens [entry]; the first overlay to open fixes the control focus returns to. */
    internal fun open(entry: OverlayEntry) {
        if (entries.isEmpty()) restoreTarget = lastFocused
        entries += entry
    }

    /**
     * Returns focus to the control that held it before the overlays opened. The
     * window may already have moved focus to a default control when the last
     * overlay left, so the target is the one fixed in [open], not the latest.
     */
    internal fun restoreFocus(): Boolean {
        val target = restoreTarget ?: return false
        lastFocused = target
        return runCatching { target.requestFocus() }.getOrDefault(false)
    }
}

/** The window's overlay host; null outside [ProvideOverlayHost] (previews, tests). */
val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/**
 * Height at the top of the window that overlays leave uncovered: a screen whose top bar
 * must stay usable under any sheet (the flight screen's STOP) provides its top bar's
 * bottom edge. Sheet, dialog and menu scrims start below it, so a press there reaches
 * the screen first.
 */
val LocalOverlayTopInset = staticCompositionLocalOf { 0.dp }

/**
 * Hosts in-window overlays for [content]. Wrap the app root once, inside the
 * theme and controller locals, so overlays draw above every surface. When the
 * last overlay closes while a controller is the active input, focus returns to
 * the control that held it before the overlay opened.
 */
@Composable
fun ProvideOverlayHost(content: @Composable () -> Unit) {
    val host = remember { OverlayHost() }
    val controllerActive by rememberUpdatedState(LocalControllerUi.current.active)
    LaunchedEffect(host) {
        var open = false
        snapshotFlow { host.entries.isNotEmpty() }.collect { nowOpen ->
            if (open && !nowOpen && controllerActive) {
                // The closed overlay's focus trap leaves the tree on the next frame.
                withFrameNanos { }
                host.restoreFocus()
            }
            open = nowOpen
        }
    }
    CompositionLocalProvider(LocalOverlayHost provides host) {
        Box(Modifier.fillMaxSize()) {
            content()
            host.entries.forEach { entry ->
                key(entry) {
                    CompositionLocalProvider(entry.locals) { entry.Render() }
                }
            }
        }
    }
}

/**
 * Draws [content] full-window in the overlay host for as long as this call stays in
 * composition. The content keeps the caller's composition locals (view model store,
 * lifecycle, theme, controller state) and recomposes with the caller's state. A new
 * [key] re-opens the overlay on top. Without a host the content draws in place.
 */
@Composable
fun Overlay(key: Any = Unit, content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        content()
        return
    }
    val locals = currentCompositionLocalContext
    val current = rememberUpdatedState(content)
    val entry = remember(key) { OverlayEntry(locals, current) }
    SideEffect { entry.locals = locals }
    DisposableEffect(host, entry) {
        host.open(entry)
        onDispose { host.entries -= entry }
    }
}
