package com.altnautica.gcs.core.skills.cockpit

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The link notification as a stop control: whether the operator can reach it now, and whether the lock screen hides it. */
interface NotificationControls {
    /** True while the link notification shows Pause, Land and Return and the operator can reach them now. */
    fun reachable(): Boolean

    /** True when the lock screen hides the link notification (a system setting or the channel's visibility). */
    fun lockScreenHidden(): Boolean
}

/**
 * The stop controls outside the cockpit screen: the link notification's Pause, Land and
 * Return. An open cockpit registers how it runs a built-in; the notification offers the
 * controls while one is registered and runs them through the newest cockpit, on the
 * caller's (main) thread, so they take the cockpit's own route: the pilot channel, and the
 * flight controller when the pilot does not answer.
 */
@Singleton
class StopControls @Inject constructor(val notification: NotificationControls) {
    private val handlers = ArrayList<(String) -> Unit>()
    private val _available = MutableStateFlow(false)

    /** True while an open cockpit can run the notification's controls. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /** Registers [run] (a built-in id to run); closing the handle unregisters it. */
    fun register(run: (builtinId: String) -> Unit): AutoCloseable {
        synchronized(handlers) {
            handlers += run
            _available.value = true
        }
        return AutoCloseable {
            synchronized(handlers) {
                handlers.remove(run)
                _available.value = handlers.isNotEmpty()
            }
        }
    }

    /** Runs [builtinId] in the newest open cockpit; false when no cockpit is open. */
    fun run(builtinId: String): Boolean {
        val run = synchronized(handlers) { handlers.lastOrNull() } ?: return false
        run(builtinId)
        return true
    }
}
