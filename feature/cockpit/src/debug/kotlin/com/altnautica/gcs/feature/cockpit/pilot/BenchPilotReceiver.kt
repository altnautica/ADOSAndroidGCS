package com.altnautica.gcs.feature.cockpit.pilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PhoneRadioUplink
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.skills.MonotonicClock
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Flies a drone's pilot over the phone radio from the fleet bench, through
 * the cockpit's own pilot channel on the radio pilot link (the path the
 * cockpit takes when the drone's only reach is the phone radio). Debug builds
 * only.
 *
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PILOT_OPEN --es node <id> [--ei hz 50]
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PILOT_INPUT --es node <id> [--es axes 0,0.5,0,0] [--ei ms 2000]
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PILOT_RELEASE --es node <id>
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PILOT_TAKE --es node <id>
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PILOT_HB --es node <id>
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PILOT_ACTION --es node <id> --es name pause
 *     adb shell am broadcast -p <package> -a com.altnautica.gcs.bench.PILOT_BYE --es node <id>
 *
 * Each logs one line under the `AdosPhonePilot` tag; the pilot's `state`
 * frames are logged as they arrive (owner, input source and age).
 */
@AndroidEntryPoint
class BenchPilotReceiver : BroadcastReceiver() {

    @Inject lateinit var uplink: Optional<PhoneRadioUplink>

    @Inject lateinit var clock: MonotonicClock

    override fun onReceive(context: Context, intent: Intent) {
        val node = intent.getStringExtra("node") ?: return log("${intent.action} refused=no_node")
        val radio = uplink.orElse(null) ?: return log("${intent.action} refused=no_phone_radio")
        val pending = goAsync()
        scope.launch {
            try {
                handle(intent, node, radio)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(intent: Intent, node: String, radio: PhoneRadioUplink) {
        if (intent.action == PILOT_OPEN) {
            channels.remove(node)?.leave()
            val hz = intent.getIntExtra("hz", PilotStickStreamer.RATE_HZ)
            val counted = CountingUplink(radio)
            counters[node] = counted
            val link = RadioPilotLink(counted, DeviceId(node), { hz })
            val channel = PilotChannel(scope, clock, mainTicker = BenchTicker()) { outgoing -> link.open(outgoing) }
            channels[node] = channel
            channel.start()
            scope.launch { channel.state.collect { s -> s?.let { log("state node=$node owner=${it.owner} input_src=${it.inputSrc} input_age_ms=${it.inputAgeMs}") } } }
            val up = withTimeoutOrNull(OPEN_WAIT_MS) { channel.connected.first { it } } == true
            return log("pilot_open node=$node connected=$up ${counted.report()}")
        }
        val channel = channels[node] ?: return log("${intent.action} node=$node refused=not_open")
        val counted = counters.getValue(node)
        val sentBefore = counted.up.get()
        fun air() = "uplink_frames=${counted.up.get() - sentBefore} ${counted.report()}"
        when (intent.action) {
            PILOT_INPUT -> {
                val axes = (intent.getStringExtra("axes") ?: "0,0.5,0,0").split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (axes.size != 4) return log("pilot_input node=$node refused=bad_axes")
                val ms = intent.getIntExtra("ms", DEFAULT_INPUT_MS)
                channel.beginSession()
                val frame = PilotInput(axes[0], axes[1], axes[2], axes[3], 0)
                val end = clock.nowMs() + ms
                var frames = 0
                while (clock.nowMs() < end) {
                    channel.input(frame)
                    frames++
                    delay(1_000L / PilotStickStreamer.RATE_HZ)
                }
                delay(FLUSH_MS)
                log("pilot_input node=$node frames=$frames ms=$ms ${air()} connected=${channel.connected.value}")
            }
            PILOT_RELEASE -> {
                channel.release()
                delay(FLUSH_MS)
                log("pilot_release node=$node ${air()}")
            }
            PILOT_TAKE -> {
                val sent = channel.takeSession()
                delay(FLUSH_MS)
                log("pilot_take node=$node sent=$sent ${air()}")
            }
            PILOT_HB -> {
                channel.watchdog()
                delay(FLUSH_MS)
                log("pilot_hb node=$node connected=${channel.connected.value} ${air()}")
            }
            PILOT_ACTION -> {
                val name = intent.getStringExtra("name") ?: return log("pilot_action node=$node refused=no_name")
                log("pilot_action node=$node name=$name result=${channel.action(PilotAction(name))} ${air()}")
            }
            PILOT_BYE -> {
                channels.remove(node)
                channel.leave()
                log("pilot_bye node=$node ${air()}")
            }
        }
    }

    /**
     * The phone radio as the pilot link sees it, counting what crosses the air:
     * `pilot.v1` requests the uplink took ([up]) and pilot bodies the drone sent
     * down ([down], before any translation), so a bench run tells "the drone sent
     * nothing" apart from "the phone could not read it" and measures the real
     * stick rate.
     */
    private class CountingUplink(private val inner: PhoneRadioUplink) : PhoneRadioUplink by inner {
        val up = AtomicInteger()
        val down = AtomicInteger()

        override fun sendPilot(node: DeviceId, body: ByteArray): Boolean = inner.sendPilot(node, body).also { if (it) up.incrementAndGet() }

        override fun pilotFrom(node: DeviceId): Flow<ByteArray> = inner.pilotFrom(node).onEach { down.incrementAndGet() }

        fun report() = "uplink_total=${up.get()} downlink_total=${down.get()} transmitting=${inner.transmitting.value}"
    }

    /** The no-RC heartbeat tick without the main looper: a bench has no screen to keep reachable. */
    private class BenchTicker : Ticker {
        private var job: kotlinx.coroutines.Job? = null

        override fun start(tick: () -> Unit) {
            job?.cancel()
            job = scope.launch {
                while (true) {
                    tick()
                    delay(PilotChannel.TICK_MS)
                }
            }
        }

        override fun stop() {
            job?.cancel()
            job = null
        }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    companion object {
        const val PILOT_OPEN = "com.altnautica.gcs.bench.PILOT_OPEN"
        const val PILOT_INPUT = "com.altnautica.gcs.bench.PILOT_INPUT"
        const val PILOT_RELEASE = "com.altnautica.gcs.bench.PILOT_RELEASE"
        const val PILOT_TAKE = "com.altnautica.gcs.bench.PILOT_TAKE"
        const val PILOT_HB = "com.altnautica.gcs.bench.PILOT_HB"
        const val PILOT_ACTION = "com.altnautica.gcs.bench.PILOT_ACTION"
        const val PILOT_BYE = "com.altnautica.gcs.bench.PILOT_BYE"
        private const val TAG = "AdosPhonePilot"
        private const val OPEN_WAIT_MS = 10_000L
        private const val DEFAULT_INPUT_MS = 2_000

        /** How long a single-frame op waits for the channel's queue to reach the uplink before counting it. */
        private const val FLUSH_MS = 200L
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val channels = ConcurrentHashMap<String, PilotChannel>()
        private val counters = ConcurrentHashMap<String, CountingUplink>()
    }
}
