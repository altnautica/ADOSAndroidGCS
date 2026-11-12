package com.altnautica.gcs.feature.configure.params

import com.altnautica.gcs.core.model.CommandResult

/** One row of the parameter list. */
data class ParamRow(
    val name: String,
    val value: Double,
    val type: Int,
    val meta: ParamMeta?,
) {
    /** Differs from the documented default. */
    val modified: Boolean get() = meta?.defaultValue?.let { !ParamFile.sameValue(it, value) } == true
    val group: String get() = meta?.group ?: ParamMetadataParser.groupFromName(name)
}

data class ParamFilter(val query: String = "", val modifiedOnly: Boolean = false, val showAdvanced: Boolean = false)

/**
 * A write waiting for the operator: armed vehicles need an explicit confirmation, and so does a
 * vehicle whose arm state is unknown ([armUnknown]: no fresh heartbeat), which is treated as armed.
 */
data class PendingWrite(val name: String, val value: Double, val armUnknown: Boolean = false)

/**
 * Parameters loaded from a file, compared with the vehicle and awaiting apply. Every changed
 * row starts selected except those refused by the single-edit checks ([invalid], with the
 * reason); the operator unticks the ones to leave alone.
 */
data class ImportReview(
    val comparison: ParamComparison,
    val deselected: Set<String> = emptySet(),
    val invalid: Map<String, ParamInvalidReason> = emptyMap(),
    val applied: Int = 0,
    val failed: List<String> = emptyList(),
    val running: Boolean = false,
    /** The value sent and awaiting the flight controller's echo while applying. */
    val inFlight: String? = null,
) {
    val selected: List<ParamDiff> get() = comparison.changed.filter { it.name !in deselected && it.name !in invalid }
}

sealed interface ParamMessage {
    data class Saved(val name: String, val value: Double, val rebootRequired: Boolean) : ParamMessage
    data class Clamped(val name: String, val requested: Double, val actual: Double) : ParamMessage
    data class WriteFailed(val name: String, val reason: ParamWriteFailure) : ParamMessage
    data class Invalid(val name: String, val reason: ParamInvalidReason) : ParamMessage
    data class LoadIncomplete(val missing: Int) : ParamMessage
    data class FileUnreadable(val reason: String) : ParamMessage
    data object FileSaved : ParamMessage
    data object FileWriteFailed : ParamMessage
    data class Reboot(val result: CommandResult) : ParamMessage

    /**
     * A file apply closed part-way: [applied] of [total] values were confirmed before it stopped;
     * [unconfirmed] names the value that was sent and whose echo had not arrived, so it may or may
     * not have been written.
     */
    data class ImportStopped(val applied: Int, val total: Int, val unconfirmed: String? = null) : ParamMessage
}

enum class ParamWriteFailure { Timeout, UnknownParam, NoLink, Other }

sealed interface ParametersUiState {
    data object NoVehicle : ParametersUiState
    data class Loading(val received: Int, val count: Int) : ParametersUiState
    data class LoadFailed(val reason: String) : ParametersUiState
    data class Content(
        val rows: List<ParamRow>,
        /** Every loaded parameter, unfiltered; Setup pages pick curated names from it. */
        val byName: Map<String, ParamRow>,
        val total: Int,
        val filter: ParamFilter,
        val armed: Boolean,
        /** MAV_TYPE from the vehicle heartbeat. */
        val mavType: Int?,
        val hasMetadata: Boolean,
        val loading: Boolean,
        /** The newest heartbeat is younger than the shared freshness limit. */
        val linkFresh: Boolean,
    ) : ParametersUiState
}
