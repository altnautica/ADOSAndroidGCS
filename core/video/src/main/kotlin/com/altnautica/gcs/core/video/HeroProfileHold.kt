package com.altnautica.gcs.core.video

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.agent.model.VideoProfileState
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** The agent calls a [HeroProfileHold] makes; an [AgentClient] in the app. */
interface VideoProfileAgent {
    suspend fun statusFull(): AgentResult<StatusFull>
    suspend fun setVideoProfile(profile: String, holder: String? = null, leaseS: Int? = null): AgentResult<VideoProfileState>
    suspend fun releaseVideoProfile(holder: String): AgentResult<VideoProfileState>
}

/**
 * Holds a LAN-direct drone on the `hero` video profile while the flight view plays it, and
 * lets go when the last viewer closes.
 *
 * A drone boots on `thumbnail` (320x180 at 1 fps) so a fleet powering up together does not
 * flood the shared channel, and waits to be promoted. The hold is a lease on the agent's
 * `POST /api/video/profile`:
 * - this app takes the lease under one holder id ([holderId]), renews it every
 *   [RENEW_MS] (a third of the lease) on a fixed cadence, and releases it with `DELETE`
 *   when the last viewer goes, so a closed or crashed app expires on its own and never
 *   strands the drone on hero;
 * - a failed renewal is retried after each of [RENEW_RETRY_MS] (then every last step)
 *   until the next scheduled renewal replaces it, so one lost or timed-out request never
 *   lets the lease lapse;
 * - viewers of the same drone share one lease, counted per [DeviceId]; a drone whose agent
 *   moves to another base URL keeps that one hold, which follows it there (renewals and the
 *   release go to the new address), so the old address never releases or demotes it;
 * - the release waits [RELEASE_GRACE_MS], so leaving and re-entering the flight view does
 *   not respawn the encoder twice;
 * - a ground station is never promoted, nor an agent that does not report a profile or
 *   does not serve the route;
 * - any other failed promotion (the request budget, a 503 while the video service
 *   starts, a timeout) is retried after each of [PROMOTE_BACKOFF_MS], then every last
 *   step, until the hold is dropped: a view that leaves and returns within the release
 *   grace finds its promotion still retrying.
 * An agent that predates leases answers without a `leases` list; there the hold falls back
 * to a plain set, promoting only a drone it saw on `thumbnail` and demoting only what it
 * promoted, once its last viewer goes.
 */
@Singleton
class HeroProfileHold(
    private val scope: CoroutineScope,
    val holderId: String,
    private val agentFor: suspend (DeviceId) -> VideoProfileAgent?,
) {

    @Inject
    constructor(clients: AgentClientFactory, @VideoScope scope: CoroutineScope) :
        this(scope, newHolderId(), { node -> clients.forNode(node)?.let(::ClientProfileAgent) })

    private enum class Outcome { Lease, Legacy, None }

    /** One drone's hold; its fields change under the [holds] lock. */
    private class Hold(var agentUrl: String) {
        var count = 1
        var releaseJob: Job? = null
        var renewJob: Job? = null
        var moveJob: Job? = null

        /** The agent the promotion or the latest move reached; renewals and the release use it. */
        var agent: VideoProfileAgent? = null
        lateinit var promoted: Deferred<Outcome>
    }

    private val holds = HashMap<DeviceId, Hold>()

    /**
     * Holds the agent [node] serving at [agentUrl] on `hero`. A node already held at another
     * URL keeps its hold, which moves to [agentUrl]. Returns the release; calling it more than
     * once releases once.
     */
    fun acquire(node: DeviceId, agentUrl: String): () -> Unit {
        val hold = synchronized(holds) {
            holds[node]?.apply {
                releaseJob?.cancel()
                releaseJob = null
                count++
                if (this.agentUrl != agentUrl) {
                    this.agentUrl = agentUrl
                    moveJob?.cancel()
                    moveJob = scope.launch { move(node, this@apply) }
                }
            } ?: Hold(agentUrl).also { created ->
                holds[node] = created
                created.promoted = scope.async { promote(node, created) }
            }
        }
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) release(node, hold)
        }
    }

    /** The hold is still the node's; a release in its grace leaves it so until the grace ends. */
    private fun current(node: DeviceId, hold: Hold): Boolean = holds[node] === hold

    /** Each attempt resolves the node's agent again, so a retry follows a node that moved. */
    private suspend fun promote(node: DeviceId, hold: Hold): Outcome {
        var attempt = 0
        while (true) {
            val agent = agentFor(node) ?: return Outcome.None
            attemptPromote(agent)?.let { outcome ->
                synchronized(holds) {
                    hold.agent = agent
                    if (outcome == Outcome.Lease && current(node, hold)) hold.renewJob = scope.launch { renew(agent, immediately = false) }
                }
                return outcome
            }
            delay(PROMOTE_BACKOFF_MS[attempt.coerceAtMost(PROMOTE_BACKOFF_MS.lastIndex)])
            attempt++
            if (!synchronized(holds) { current(node, hold) }) return Outcome.None
        }
    }

    /**
     * Points a hold at the node's new address once its promotion has settled. A held lease is
     * renewed there at once and then on the usual cadence; a pre-lease promotion is demoted
     * there when the hold ends. Nothing is sent to the old address.
     */
    private suspend fun move(node: DeviceId, hold: Hold) {
        val outcome = hold.promoted.await()
        if (outcome == Outcome.None) return
        val agent = agentFor(node) ?: return
        synchronized(holds) {
            if (!current(node, hold)) return
            hold.agent = agent
            if (outcome == Outcome.Lease) {
                hold.renewJob?.cancel()
                hold.renewJob = scope.launch { renew(agent, immediately = true) }
            }
        }
    }

    /** One promotion; null when it failed in a way worth retrying. */
    private suspend fun attemptPromote(agent: VideoProfileAgent): Outcome? {
        val full = when (val r = agent.statusFull()) {
            is AgentResult.Ok -> r.value
            is AgentResult.Err -> return if (r.error.isTransient()) null else Outcome.None
        }
        if (full.profile == GROUND_STATION) return Outcome.None
        val observed = full.telemetry?.videoProfile
        if (observed != HERO && observed != THUMBNAIL) return Outcome.None
        val applied = when (val r = agent.setVideoProfile(HERO, holderId, LEASE_S)) {
            is AgentResult.Ok -> r.value
            is AgentResult.Err -> return if (r.error.isTransient()) null else Outcome.None
        }
        if (applied.leases != null) return Outcome.Lease
        // A pre-lease agent applied a plain set, a no-op on a drone already on hero, so only a
        // promotion from thumbnail is ours to undo.
        return if (observed == THUMBNAIL) Outcome.Legacy else Outcome.None
    }

    /**
     * Renews every [RENEW_MS] counted from the previous renewal's start, not its answer, so a
     * slow or timed-out request does not push the schedule back. Each scheduled renewal
     * retries its failures until it succeeds or the next one replaces it. [immediately] adds a
     * renewal now, before the first scheduled one.
     */
    private suspend fun renew(agent: VideoProfileAgent, immediately: Boolean) = coroutineScope {
        var attempt: Job? = if (immediately) launch { renewUntilAccepted(agent) } else null
        while (true) {
            delay(RENEW_MS)
            attempt?.cancel()
            attempt = launch { renewUntilAccepted(agent) }
        }
    }

    private suspend fun renewUntilAccepted(agent: VideoProfileAgent) {
        var retry = 0
        while (agent.setVideoProfile(HERO, holderId, LEASE_S) is AgentResult.Err) {
            delay(RENEW_RETRY_MS[retry.coerceAtMost(RENEW_RETRY_MS.lastIndex)])
            retry++
        }
    }

    private fun release(node: DeviceId, hold: Hold) = synchronized(holds) {
        hold.count--
        if (hold.count > 0) return@synchronized
        hold.releaseJob = scope.launch {
            delay(RELEASE_GRACE_MS)
            val drop = synchronized(holds) {
                if (hold.count > 0 || !current(node, hold)) return@synchronized false
                holds.remove(node)
                hold.renewJob?.cancel()
                hold.moveJob?.cancel()
                true
            }
            if (!drop) return@launch
            val outcome = hold.promoted.await()
            val agent = synchronized(holds) { hold.agent } ?: return@launch
            when (outcome) {
                Outcome.Lease -> agent.releaseVideoProfile(holderId)
                Outcome.Legacy -> agent.setVideoProfile(THUMBNAIL)
                Outcome.None -> Unit
            }
        }
    }

    private class ClientProfileAgent(private val client: AgentClient) : VideoProfileAgent {
        override suspend fun statusFull() = client.statusFull()
        override suspend fun setVideoProfile(profile: String, holder: String?, leaseS: Int?) =
            client.setVideoProfile(profile, holder, leaseS)
        override suspend fun releaseVideoProfile(holder: String) = client.releaseVideoProfile(holder)
    }

    companion object {
        const val HERO = "hero"
        const val THUMBNAIL = "thumbnail"
        const val GROUND_STATION = "ground-station"

        /** The lease this app asks for, in seconds; renewed every third of it. */
        const val LEASE_S = 30
        const val RENEW_MS = LEASE_S * 1_000L / 3

        /** The waits before each retry of a failed renewal; the last repeats. */
        val RENEW_RETRY_MS: List<Long> = listOf(2_000L, 4_000L)

        /** How long the last released hold waits before releasing the lease. */
        const val RELEASE_GRACE_MS = 5_000L

        /** The waits before each retry of a failed promotion; the last repeats while held. */
        val PROMOTE_BACKOFF_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L)

        /**
         * A failure that may pass: the request budget, a server error (the video service still
         * starting), or an unreachable agent. Refusals of the caller or the route are final.
         */
        private fun AgentError.isTransient() = this is AgentError.Server || this is AgentError.Network

        private fun newHolderId(): String = "android:" + UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
