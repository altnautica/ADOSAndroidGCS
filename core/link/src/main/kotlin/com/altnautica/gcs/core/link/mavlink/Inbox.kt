package com.altnautica.gcs.core.link.mavlink

import com.altnautica.gcs.core.mavlink.messages.MavMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Queue of target messages matching [accept], subscribed synchronously at
 * creation so replies to a request sent right after cannot be missed.
 * Protocol state machines read it with a per-step timeout.
 */
class Inbox internal constructor(scope: CoroutineScope, link: MavlinkLink, accept: (MavMessage) -> Boolean) {
    private val channel = Channel<MavMessage>(Channel.UNLIMITED)
    private val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        link.targetMessages().collect { if (accept(it.message)) channel.send(it.message) }
    }

    /** Next matching message, or null after [timeoutMs]. */
    suspend fun receive(timeoutMs: Long): MavMessage? = withTimeoutOrNull(timeoutMs) { channel.receive() }

    /** Next matching message, waiting as long as it takes (wrap in a timeout). */
    suspend fun receive(): MavMessage = channel.receive()

    /** Drops anything already queued (stale replies to an earlier attempt). */
    fun drain() {
        while (channel.tryReceive().isSuccess) Unit
    }

    fun close() {
        job.cancel()
        channel.close()
    }
}

/** Opens an [Inbox] on [link]; close it when the exchange ends. */
fun CoroutineScope.inbox(link: MavlinkLink, accept: (MavMessage) -> Boolean): Inbox = Inbox(this, link, accept)

/** Runs [block] with an inbox that is closed afterwards. */
suspend fun <T> MavlinkLink.withInbox(accept: (MavMessage) -> Boolean, block: suspend (Inbox) -> T): T =
    kotlinx.coroutines.coroutineScope {
        val inbox = inbox(this@withInbox, accept)
        try {
            block(inbox)
        } finally {
            inbox.close()
        }
    }
