package com.altnautica.gcs.core.designsystem.component.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

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
}

/** The window's overlay host; null outside [ProvideOverlayHost] (previews, tests). */
val LocalOverlayHost = staticCompositionLocalOf<OverlayHost?> { null }

/**
 * Hosts in-window overlays for [content]. Wrap the app root once, inside the
 * theme and controller locals, so overlays draw above every surface.
 */
@Composable
fun ProvideOverlayHost(content: @Composable () -> Unit) {
    val host = remember { OverlayHost() }
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
        host.entries += entry
        onDispose { host.entries -= entry }
    }
}
