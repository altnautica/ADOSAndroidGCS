package com.altnautica.gcs.core.extensions.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** An extension asking the operator to approve an action on a vehicle. */
data class ConfirmPrompt(
    val id: String,
    val pluginId: String,
    val title: String,
    val body: String,
    /** True when the vehicle may be flying (armed or arm state unknown). */
    val critical: Boolean,
    internal val answer: CompletableDeferred<Boolean>,
)

/**
 * Pending operator approvals raised by extension handlers. A host composable renders the
 * head of the queue as a hold-to-confirm sheet and answers it.
 */
@Singleton
class ConfirmBroker @Inject constructor() {
    private val queue = MutableStateFlow<List<ConfirmPrompt>>(emptyList())
    val pending: StateFlow<List<ConfirmPrompt>> = queue.asStateFlow()

    /** Suspends until the operator approves (true) or declines (false). */
    suspend fun request(pluginId: String, title: String, body: String, critical: Boolean): Boolean {
        val prompt = ConfirmPrompt(UUID.randomUUID().toString(), pluginId, title, body, critical, CompletableDeferred())
        queue.update { it + prompt }
        try {
            return prompt.answer.await()
        } finally {
            queue.update { list -> list.filterNot { it.id == prompt.id } }
        }
    }

    fun answer(id: String, approved: Boolean) {
        queue.value.firstOrNull { it.id == id }?.answer?.complete(approved)
    }

    private val owners = HashMap<String, String>()

    /** One host renders each prompt: true when [owner] holds (or just took) [id]. */
    fun claim(id: String, owner: String): Boolean = synchronized(owners) {
        owners.keys.retainAll(queue.value.map { it.id }.toSet())
        owners.getOrPut(id) { owner } == owner
    }

    /** Drop every claim [owner] holds (its host left composition). */
    fun release(owner: String) = synchronized(owners) { owners.values.removeAll { it == owner } }
}

enum class NoticeLevel { Info, Success, Warning, Error }

data class ExtensionNotice(val pluginId: String, val message: String, val level: NoticeLevel)

/** Rate-limited notices extensions post for the operator. */
@Singleton
class ExtensionNotifier @Inject constructor() {
    private val events = MutableSharedFlow<ExtensionNotice>(extraBufferCapacity = 16)
    val notices: SharedFlow<ExtensionNotice> = events.asSharedFlow()

    private val windows = HashMap<String, ArrayDeque<Long>>()

    var clock: () -> Long = System::currentTimeMillis
        internal set

    /** False when the extension exceeded [MAX_PER_WINDOW] notices in [WINDOW_MS]. */
    fun post(pluginId: String, message: String, level: NoticeLevel): Boolean {
        val now = clock()
        synchronized(windows) {
            val w = windows.getOrPut(pluginId) { ArrayDeque() }
            while (w.isNotEmpty() && now - w.first() >= WINDOW_MS) w.removeFirst()
            if (w.size >= MAX_PER_WINDOW) return false
            w.addLast(now)
        }
        events.tryEmit(ExtensionNotice(pluginId, message.take(MAX_CHARS), level))
        return true
    }

    companion object {
        const val MAX_PER_WINDOW = 5
        const val WINDOW_MS = 10_000L
        const val MAX_CHARS = 280
    }
}

/** Per-extension command budget: [MAX] approved commands per [WINDOW_MS]. */
class CommandRateLimiter(private val clock: () -> Long = System::currentTimeMillis) {
    private var windowStart = Long.MIN_VALUE
    private var count = 0

    @Synchronized
    fun available(): Boolean = windowStart == Long.MIN_VALUE || clock() - windowStart >= WINDOW_MS || count < MAX

    @Synchronized
    fun take(): Boolean {
        val now = clock()
        if (windowStart == Long.MIN_VALUE || now - windowStart >= WINDOW_MS) {
            windowStart = now
            count = 1
            return true
        }
        if (count >= MAX) return false
        count++
        return true
    }

    companion object {
        const val MAX = 10
        const val WINDOW_MS = 60_000L
    }
}
