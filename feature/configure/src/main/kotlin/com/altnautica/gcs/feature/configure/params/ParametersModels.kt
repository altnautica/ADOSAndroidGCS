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

/** A write waiting for the operator: armed vehicles need an explicit confirmation. */
data class PendingWrite(val name: String, val value: Double)

/**
 * Parameters loaded from a file, compared with the vehicle and awaiting apply. Every changed
 * row starts selected; the operator unticks the ones to leave alone.
 */
data class ImportReview(
    val comparison: ParamComparison,
    val deselected: Set<String> = emptySet(),
    val applied: Int = 0,
    val failed: List<String> = emptyList(),
    val running: Boolean = false,
) {
    val selected: List<ParamDiff> get() = comparison.changed.filter { it.name !in deselected }
}

sealed interface ParamMessage {
    data class Saved(val name: String, val value: Double, val rebootRequired: Boolean) : ParamMessage
    data class Clamped(val name: String, val requested: Double, val actual: Double) : ParamMessage
    data class WriteFailed(val name: String, val reason: ParamWriteFailure) : ParamMessage
    data class Invalid(val name: String, val reason: ParamInvalidReason) : ParamMessage
    data class LoadIncomplete(val missing: Int) : ParamMessage
    data class FileUnreadable(val reason: String) : ParamMessage
    data object FileSaved : ParamMessage
    data class FileWriteFailed(val reason: String?) : ParamMessage
    data class Reboot(val result: CommandResult) : ParamMessage
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
