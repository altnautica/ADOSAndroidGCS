package com.altnautica.gcs.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone radio's transmit side, for the modules that act on drones over
 * it without depending on the radio itself. Bound by the phone radio; absent
 * where the app is built without one.
 *
 * Everything here goes out only while [transmitting]: the phone holds the
 * uplink and its state is not down. Otherwise sends answer false and calls
 * answer [RadioCall.Failed].
 */
interface PhoneRadioUplink {
    /** True while the phone holds the uplink and it is not down. */
    val transmitting: StateFlow<Boolean>

    /** Whether [node] is heard on the phone radio now. */
    fun hears(node: DeviceId): Boolean

    /** One `pilot.v1` request body for [node]'s pilot, sent as one datagram; false when it could not go out. */
    fun sendPilot(node: DeviceId, body: ByteArray): Boolean

    /** The `pilot.v1` bodies [node]'s pilot sends down (its `state`). */
    fun pilotFrom(node: DeviceId): Flow<ByteArray>

    /**
     * One HTTP call to [node]'s agent over the radio's relay request lane.
     * [path] is absolute with any query, still percent-encoded.
     */
    suspend fun call(node: DeviceId, method: String, path: String, contentType: String?, body: ByteArray): RadioCall
}

/** How a call over the phone radio ended. */
sealed interface RadioCall {
    /** The drone answered with its own HTTP status. */
    class Answered(val status: Int, val body: ByteArray, val headers: List<Pair<String, String>>) : RadioCall

    /** Nothing usable came back; [reason] is a short machine-readable cause. */
    data class Failed(val reason: String) : RadioCall
}
