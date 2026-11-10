package com.altnautica.gcs.core.skills.builtin

import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Which vehicles an operator on this surface sent into a return or landing, by vehicle id. A
 * note is taken only once the command was accepted or queued. It waits up to [ENTRY_GRACE_MS]
 * (on the session clock, from the first heartbeat after the note) for the vehicle to enter such
 * a mode, lasts while it stays in that mode, and ends on any mode change, a return turning into
 * a landing included: the next entry is the FC's own again.
 */
internal object OperatorRecovery {
    private class Note {
        @Volatile var firstSeenMs: Long? = null
        @Volatile var enteredMode: String? = null
    }

    const val ENTRY_GRACE_MS = 10_000L
    private val notes = ConcurrentHashMap<DeviceId, Note>()

    /** Records an operator return or landing for [id] when [result] shows the command went out. */
    fun noteIfSent(id: DeviceId?, result: CommandResult) {
        if (id != null && (result == CommandResult.Accepted || result is CommandResult.Queued)) notes[id] = Note()
    }

    /** Called on every heartbeat with the FC's mode and the session clock. */
    fun observeMode(id: DeviceId, modeName: String, nowMs: Long) {
        val n = notes[id] ?: return
        val mode = modeName.uppercase()
        val entered = n.enteredMode
        when {
            entered != null -> if (mode != entered) notes.remove(id, n)
            mode in FAILSAFE_MODES -> n.enteredMode = mode
            else -> {
                val first = n.firstSeenMs ?: nowMs.also { n.firstSeenMs = it }
                if (nowMs - first > ENTRY_GRACE_MS) notes.remove(id, n)
            }
        }
    }

    fun commanded(id: DeviceId): Boolean = notes.containsKey(id)
}
