package com.altnautica.gcs.core.link.param

import kotlinx.coroutines.flow.StateFlow

/** One autopilot parameter. Integer types are carried exactly in [value]. */
data class ParamValue(
    val name: String,
    val value: Double,
    /** MAV_PARAM_TYPE. */
    val type: Int,
    val index: Int,
)

/** Parameters received so far; [count] is the autopilot's `param_count` (0 until known). */
data class ParamSnapshot(
    val count: Int,
    val received: Int,
    val values: Map<String, ParamValue>,
) {
    val complete: Boolean get() = count > 0 && received >= count

    companion object {
        val EMPTY = ParamSnapshot(0, 0, emptyMap())
    }
}

sealed interface ParamLoadResult {
    data class Complete(val count: Int) : ParamLoadResult

    /** Gave up after the re-request rounds with [missing] indices still absent. */
    data class Incomplete(val count: Int, val missing: Int) : ParamLoadResult
    data class Failed(val reason: String) : ParamLoadResult
}

sealed interface ParamSetResult {
    /** The autopilot echoed the requested value. */
    data class Confirmed(val value: ParamValue) : ParamSetResult

    /** The autopilot answered with a different value (rejected or clamped). */
    data class Mismatch(val requested: Double, val actual: ParamValue) : ParamSetResult
    data object UnknownParam : ParamSetResult
    data object Timeout : ParamSetResult
    data class Failed(val reason: String) : ParamSetResult
}

/** Parameter protocol: list download with gap repair, confirmed writes. */
interface ParamService {
    /** Updated at most 10 times a second while values arrive. */
    val snapshot: StateFlow<ParamSnapshot>
    val loading: StateFlow<Boolean>

    /** Downloads the full list (`PARAM_REQUEST_LIST`), re-requesting missing indices. */
    suspend fun refresh(): ParamLoadResult

    /** Writes [name] and waits for the matching `PARAM_VALUE`. */
    suspend fun set(name: String, value: Double): ParamSetResult

    /** Reads one parameter by name (`PARAM_REQUEST_READ`). */
    suspend fun read(name: String): ParamValue?
}
