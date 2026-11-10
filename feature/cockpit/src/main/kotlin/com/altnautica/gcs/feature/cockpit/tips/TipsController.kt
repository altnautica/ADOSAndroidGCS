package com.altnautica.gcs.feature.cockpit.tips

import com.altnautica.gcs.core.skills.tips.TipDecision
import com.altnautica.gcs.core.skills.tips.TipsContext
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.core.skills.tips.TipsState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the flight screen reports to the tip layer: the facts the tip queue depends on. */
data class TipFacts(
    val armed: Boolean,
    val airborne: Boolean,
    val sheetOrMenuOpen: Boolean,
    val tourVisible: Boolean,
    val skillsAvailable: Boolean,
    val gotoOffered: Boolean,
    val gimbalAvailable: Boolean,
)

/**
 * One flight-screen open of the tip queue: when the screen opened, the tip on screen and
 * since when, the last dismissal and the tips hidden unseen on this open. [tick] asks
 * [engine] what to do with the device's [TipsState] and applies it; a dismissal or arming
 * marks the tip seen through [markSeen]. Create one per flight-screen open.
 */
class TipsController(
    private val engine: TipsEngine,
    private val clock: () -> Long,
    private val markSeen: suspend (String) -> Unit,
    private val noteFlightScreenOpen: suspend () -> Unit,
) {
    /** When this flight-screen open started. */
    val screenOpenedAtMs: Long = clock()

    private val lock = Mutex()
    private var opened = false
    private var lastDismissedAtMs: Long? = null
    private var shownAtMs: Long? = null
    private val hiddenThisOpen = LinkedHashSet<String>()

    /** Tips marked seen on this open, counted before the device store reports them back. */
    private val seenThisOpen = LinkedHashSet<String>()

    private val _shown = MutableStateFlow<TipDecision.Show?>(null)

    /** The tip on screen now, or null. */
    val shown: StateFlow<TipDecision.Show?> = _shown.asStateFlow()

    /** Counts this flight-screen open on the device, once however often it is called. */
    suspend fun open() {
        lock.withLock {
            if (opened) return
            opened = true
            withContext(NonCancellable) { noteFlightScreenOpen() }
        }
    }

    /**
     * Re-decides with the device's [state], the screen's [facts] and which anchors are laid
     * out now ([anchorsVisible]); returns the decision applied.
     */
    suspend fun tick(state: TipsState, facts: TipFacts, anchorsVisible: Map<String, Boolean>): TipDecision = lock.withLock {
        val now = clock()
        val shownId = _shown.value?.tipId
        val known = if (seenThisOpen.isEmpty()) state else state.copy(seen = (state.seen + seenThisOpen).distinct())
        val ctx = TipsContext(
            armed = facts.armed,
            airborne = facts.airborne,
            flightScreen = true,
            sheetOrMenuOpen = facts.sheetOrMenuOpen,
            tourVisible = facts.tourVisible,
            skillsAvailable = facts.skillsAvailable,
            gotoOffered = facts.gotoOffered,
            gimbalAvailable = facts.gimbalAvailable,
            anchorsVisible = anchorsVisible,
            screenOpenedAtMs = screenOpenedAtMs,
            lastDismissedAtMs = lastDismissedAtMs,
            shownTipId = shownId,
            shownAtMs = shownAtMs,
            hiddenThisOpen = hiddenThisOpen.toSet(),
        )
        val d = engine.nextTip(known, ctx, now)
        when (d) {
            is TipDecision.Show -> if (shownId != d.tipId) {
                shownAtMs = now
                _shown.value = d
            }
            is TipDecision.Hide -> hide(d.tipId, now, seen = d.markSeen)
            TipDecision.None, is TipDecision.Wait -> Unit
        }
        d
    }

    /**
     * The pilot dismissed the tip on screen: tapped it or its button, or pressed a
     * controller button. It is seen for good. False when no tip was on screen.
     */
    suspend fun dismiss(): Boolean {
        lock.withLock {
            val id = _shown.value?.tipId ?: return false
            hide(id, clock(), seen = true)
            return true
        }
    }

    /** The pilot touched the anchor [anchorId]: dismisses the tip on screen when it points at it. */
    suspend fun dismissFromAnchor(anchorId: String): Boolean {
        lock.withLock {
            val tip = _shown.value?.takeIf { it.anchor == anchorId } ?: return false
            hide(tip.tipId, clock(), seen = true)
            return true
        }
    }

    /** Hides [id]: seen for good, or unseen and not offered again until the next open. */
    private suspend fun hide(id: String, now: Long, seen: Boolean) {
        _shown.value = null
        shownAtMs = null
        lastDismissedAtMs = now
        if (seen) {
            seenThisOpen += id
            withContext(NonCancellable) { markSeen(id) }
        } else {
            hiddenThisOpen += id
        }
    }
}
