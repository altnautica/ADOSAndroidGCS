package com.altnautica.gcs.core.designsystem.component.console

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/** Screen-level controller commands, delivered to the topmost screen that handles them. */
enum class ConsoleCommand { Details, More, MoreDown, MoreUp, PrevTab, NextTab, PrevPage, NextPage, Menu, QuickMenu, Home, Capture }

/**
 * Stack of command handlers. The most recently registered enabled handler sees a
 * command first; dispatch stops at the first handler that returns true.
 */
class ConsoleCommandDispatcher {
    class Handler internal constructor(internal val onCommand: (ConsoleCommand) -> Boolean) {
        internal var enabled: Boolean = true
    }

    private val handlers = mutableStateListOf<Handler>()

    /** True when a handler took [command]. */
    fun dispatch(command: ConsoleCommand): Boolean {
        for (i in handlers.indices.reversed()) {
            val h = handlers[i]
            if (h.enabled && h.onCommand(command)) return true
        }
        return false
    }

    internal fun register(handler: Handler) {
        handlers += handler
    }

    internal fun unregister(handler: Handler) {
        handlers -= handler
    }
}

val LocalConsoleCommands = staticCompositionLocalOf<ConsoleCommandDispatcher> { error("no dispatcher") }

/** Handles console commands while in composition; [onCommand] returns true when it took the command. */
@Composable
fun ConsoleCommandHandler(enabled: Boolean = true, onCommand: (ConsoleCommand) -> Boolean) {
    val dispatcher = LocalConsoleCommands.current
    val current by rememberUpdatedState(onCommand)
    val handler = remember { ConsoleCommandDispatcher.Handler { current(it) } }
    SideEffect { handler.enabled = enabled }
    DisposableEffect(dispatcher, handler) {
        dispatcher.register(handler)
        onDispose { dispatcher.unregister(handler) }
    }
}
