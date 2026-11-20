package com.altnautica.gcs.radio.rx.uplink

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

/**
 * Decides, for one take, between the take opening the phone's transmitters
 * and a take-back call from the box, so the phone never radiates after it
 * answered a take-back:
 *
 * - before the transmitters start opening, a take-back cancels the take: it
 *   never opens them and the call is answered at once;
 * - while they open, the take-back waits: the take closes them as soon as
 *   they opened and the call is answered with whether they read quiet;
 * - once the take settled (transmitting, or ended), the take-back is the
 *   holder's ordinary one.
 */
internal class TakeGate {
    sealed interface TakeBack {
        /** The take will not open the transmitters: answer now. */
        data object Cancelled : TakeBack

        /** The transmitters are opening: answer with this, true when they closed and read quiet. */
        class Waiting(val quiet: Deferred<Boolean>) : TakeBack

        /** The take settled before the call: handle it by the handover's phase. */
        data object Settled : TakeBack
    }

    private var opening = false
    private var cancelled = false
    private var closed = false
    private var waiter: CompletableDeferred<Boolean>? = null

    /** A verified take-back call reached the phone during this take. */
    @Synchronized
    fun takeBack(): TakeBack = when {
        closed -> TakeBack.Settled
        opening -> TakeBack.Waiting(waiter ?: CompletableDeferred<Boolean>().also { waiter = it })
        else -> {
            cancelled = true
            TakeBack.Cancelled
        }
    }

    /** True when the transmitters may start opening: no take-back came first. */
    @Synchronized
    fun open(): Boolean {
        if (cancelled) return false
        opening = true
        return true
    }

    /** A take-back arrived during this take, answered or waiting. */
    @Synchronized
    fun takenBack(): Boolean = cancelled || waiter != null

    /**
     * Settles the take: with no take-back waiting, [settled] runs under the
     * gate's lock, so a later call sees its result; returns the waiting
     * take-back otherwise, which the take answers once its transmitters closed.
     */
    @Synchronized
    fun close(settled: () -> Unit = {}): CompletableDeferred<Boolean>? {
        if (!closed) {
            closed = true
            if (waiter == null) settled()
        }
        return waiter
    }
}
