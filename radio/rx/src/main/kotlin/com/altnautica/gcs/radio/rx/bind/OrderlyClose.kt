package com.altnautica.gcs.radio.rx.bind

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Ends the bind connection in order, as wfb-ng's bind client does when it
 * exits: this side's FIN, then the planes stay up until the drone has
 * acknowledged it. The drone's bind server only finishes its session when
 * its end of the connection reaches end of stream, so tearing the radio down
 * with the FIN unsent or unacknowledged leaves the drone waiting out its own
 * transfer timeout.
 *
 * Clean means the FIN was acknowledged. Once it is, the drone's own FIN gets
 * a short grace so the phone acknowledges it too.
 *
 * The close runs even when the bind was cancelled (it sits in a `finally`):
 * it ignores cancellation and is bounded by its own budget instead.
 */
class OrderlyClose(
    private val budgetMs: Long = BUDGET_MS,
    private val peerFinGraceMs: Long = PEER_FIN_GRACE_MS,
    private val settleMs: Long = SETTLE_MS,
    private val pollMs: Long = POLL_MS,
    private val nowMs: () -> Long,
) {
    /** True when the FIN went out and was acknowledged within the budget. */
    suspend fun run(finish: () -> Boolean, progress: () -> CloseProgress): Boolean = withContext(NonCancellable) {
        awaitClose(finish, progress)
    }

    private suspend fun awaitClose(finish: () -> Boolean, progress: () -> CloseProgress): Boolean {
        if (!finish()) return false
        val deadline = nowMs() + budgetMs
        var ackedAt: Long? = null
        while (true) {
            val now = nowMs()
            when (progress()) {
                CloseProgress.Complete -> {
                    // Leave time for the last acknowledgement to leave the radio.
                    delay(settleMs)
                    return true
                }
                CloseProgress.Failed, CloseProgress.NotClosing -> return false
                CloseProgress.FinAcked -> {
                    val since = ackedAt ?: now.also { ackedAt = it }
                    if (now - since >= peerFinGraceMs || now >= deadline) return true
                }
                CloseProgress.FinSent -> if (now >= deadline) return false
            }
            delay(pollMs)
        }
    }

    companion object {
        const val BUDGET_MS = 10_000L
        const val PEER_FIN_GRACE_MS = 2_000L
        const val SETTLE_MS = 100L
        const val POLL_MS = 10L
    }
}
