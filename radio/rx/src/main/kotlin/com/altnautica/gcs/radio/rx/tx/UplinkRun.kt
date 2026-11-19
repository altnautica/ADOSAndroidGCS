package com.altnautica.gcs.radio.rx.tx

import android.os.SystemClock
import android.util.Log
import com.altnautica.gcs.radio.rx.hop.PresenceBeacon
import com.altnautica.gcs.radio.rx.invite.InviteBundle
import com.altnautica.gcs.radio.usb.IUsbRadio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The phone's uplink for one radio run while it holds the uplink: the box's
 * two ground transmitters on the radio's transmit pipe, the HopAck echo, the
 * presence beacon every 10 s on the current channel, and the uplink judged
 * every 5 s from both transmitters and the radio, reported through [report].
 */
class UplinkRun(
    private val radio: IUsbRadio,
    private val bundle: InviteBundle,
    private val groundId: String,
    private val scope: CoroutineScope,
    private val channel: () -> Int,
    private val report: (UplinkState) -> Unit,
) : AutoCloseable {

    private val judge = UplinkJudge()
    private var transmitters: GroundTransmitters? = null
    private val jobs = mutableListOf<Job>()

    /** Opens the transmitters and starts the beacon and the judge; true when transmitting. */
    fun begin(): Boolean {
        val tx = try {
            radio.txFrames().use { pipe -> GroundTransmitters(pipe, bundle) }
        } catch (e: Exception) {
            Log.w(TAG, "the ground transmitters did not open", e)
            judge.radioLost()
            report(judge.state(SystemClock.elapsedRealtime()))
            return false
        }
        transmitters = tx
        val hopKey = bundle.hopKey
        jobs += scope.launch {
            while (isActive) {
                tx.control.send(PresenceBeacon.build(groundId, channel(), System.currentTimeMillis(), hopKey))
                delay(PresenceBeacon.INTERVAL_MS)
            }
        }
        jobs += scope.launch { judgeLoop(tx) }
        report(judge.state(SystemClock.elapsedRealtime()))
        return true
    }

    /** Sends on the aux uplink transmitter; false while no transmitter is open. */
    fun sendAux(payload: ByteArray): Boolean = transmitters?.auxUp?.send(payload) ?: false

    /** Echoes a verified hop announcement as the HopAck. */
    fun ack(announce: ByteArray) {
        transmitters?.control?.send(announce)
    }

    /** The drone acted on what this phone sent (it hopped after the phone's HopAck). */
    fun evidence() {
        judge.evidence(SystemClock.elapsedRealtime())
    }

    private suspend fun judgeLoop(tx: GroundTransmitters) {
        val start = SystemClock.elapsedRealtime()
        val control = TxLivenessWindow(start)
        val aux = TxLivenessWindow(start)
        while (scope.isActive) {
            delay(TxLivenessWindow.POLL_MS)
            val now = SystemClock.elapsedRealtime()
            // [framesOffered, framesSent, framesRefused, bytesOffered, bytesSent]
            val radioStats = runCatching { radio.txStats() }.getOrNull()
            if (radioStats != null) {
                val sent = radioStats[4]
                val c = tx.control.stats()
                val a = tx.auxUp.stats()
                control.observe(TxSample(c.bytesIn, c.bytesInjected, sent), now)
                aux.observe(TxSample(a.bytesIn, a.bytesInjected, sent), now)
            }
            judge.verdicts(control.evaluate(now), aux.evaluate(now))
            report(judge.state(now))
        }
    }

    override fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        transmitters?.close()
        transmitters = null
    }

    private companion object {
        const val TAG = "AdosPhoneUplink"
    }
}
