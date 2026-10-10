package com.altnautica.gcs.feature.configure.params

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.link.param.ParamLoadResult
import com.altnautica.gcs.core.link.param.ParamSetResult
import com.altnautica.gcs.core.link.param.ParamSnapshot
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.feature.configure.common.ConfigureTarget
import com.altnautica.gcs.feature.configure.common.ConfigureTargetSource
import com.altnautica.gcs.feature.configure.common.fcVariant
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

data class ParamFilter(val query: String = "", val group: String? = null, val modifiedOnly: Boolean = false, val showAdvanced: Boolean = false)

/** A write waiting for the operator: armed vehicles need an explicit confirmation. */
data class PendingWrite(val name: String, val value: Double)

/** Parameters loaded from a file, compared with the vehicle and awaiting apply. */
data class ImportReview(val comparison: ParamComparison, val applied: Int = 0, val failed: List<String> = emptyList(), val running: Boolean = false)

sealed interface ParamMessage {
    data class Saved(val name: String, val value: Double, val rebootRequired: Boolean) : ParamMessage
    data class Clamped(val name: String, val requested: Double, val actual: Double) : ParamMessage
    data class WriteFailed(val name: String, val reason: ParamWriteFailure) : ParamMessage
    data class Invalid(val name: String, val reason: ParamInvalidReason) : ParamMessage
    data class LoadIncomplete(val missing: Int) : ParamMessage
    data class FileUnreadable(val reason: String) : ParamMessage
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
        val groups: List<String>,
        val total: Int,
        val filter: ParamFilter,
        val armed: Boolean,
        /** MAV_TYPE from the vehicle heartbeat. */
        val mavType: Int?,
        val hasMetadata: Boolean,
        val loading: Boolean,
    ) : ParametersUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ParametersViewModel.Factory::class)
class ParametersViewModel internal constructor(
    nodeId: DeviceId,
    targets: ConfigureTargetSource,
    private val metadataFor: suspend (String) -> ParamMetadataSet?,
) : ViewModel() {
    @AssistedInject constructor(
        @Assisted nodeId: String,
        targets: ConfigureTargetSource,
        metadata: ParamMetadataRepository,
    ) : this(DeviceId(nodeId), targets, metadata::load)

    @AssistedFactory
    interface Factory {
        // A String id: KSP cannot name a factory method that takes an inline value class.
        fun create(nodeId: String): ParametersViewModel
    }

    private val target = targets.observe(nodeId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val filter = MutableStateFlow(ParamFilter())
    private val loadError = MutableStateFlow<String?>(null)

    private val _pending = MutableStateFlow<PendingWrite?>(null)
    val pendingWrite: StateFlow<PendingWrite?> = _pending.asStateFlow()

    private val _message = MutableStateFlow<ParamMessage?>(null)
    val message: StateFlow<ParamMessage?> = _message.asStateFlow()

    private val _import = MutableStateFlow<ImportReview?>(null)
    val importReview: StateFlow<ImportReview?> = _import.asStateFlow()

    private val heartbeat: Flow<HeartbeatState?> = target.flatMapLatest { it?.vehicle?.telemetry?.heartbeat ?: flowOf(null) }

    private val metadata: StateFlow<ParamMetadataSet?> = heartbeat
        .map { hb -> hb?.let { ParamMetadataKey.forVehicle(it.fcVariant(), it.vehicleType) } }
        .distinctUntilChanged()
        .mapLatest { key -> key?.let { metadataFor(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val snapshot: Flow<Pair<ParamSnapshot, Boolean>> = target.flatMapLatest { t ->
        t?.let { combine(it.params.snapshot, it.params.loading) { s, l -> s to l } } ?: flowOf(ParamSnapshot.EMPTY to false)
    }

    private val vehicleInfo: Flow<Pair<Boolean, Int?>> = heartbeat.map { (it?.armed == true) to it?.vehicleType }.distinctUntilChanged()

    val state: StateFlow<ParametersUiState> = combine(
        combine(target, snapshot, loadError) { t, s, e -> Triple(t, s, e) },
        metadata,
        filter,
        vehicleInfo,
    ) { (t, snap, error), meta, f, (isArmed, mavType) ->
        val (s, loading) = snap
        when {
            t == null -> ParametersUiState.NoVehicle
            // Values the FC broadcasts on its own (a changed parameter) are not a loaded list.
            !s.complete && error != null -> ParametersUiState.LoadFailed(error)
            !s.complete && (loading || s.values.isEmpty()) -> ParametersUiState.Loading(s.received, s.count)
            else -> {
                val all = s.values.values.map { ParamRow(it.name, it.value, it.type, meta?.get(it.name)) }
                ParametersUiState.Content(
                    rows = all.filter { it.matches(f) }.sortedBy { it.name },
                    byName = all.associateBy { it.name },
                    groups = all.map { it.group }.distinct().sorted(),
                    total = all.size,
                    filter = f,
                    armed = isArmed,
                    mavType = mavType,
                    hasMetadata = meta != null,
                    loading = loading,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ParametersUiState.NoVehicle)

    init {
        viewModelScope.launch {
            // Load once per newly attached vehicle unless its list is already complete.
            target.filterNotNull().distinctUntilChanged { a, b -> a.params === b.params }.collect { t ->
                if (!t.params.snapshot.value.complete) refreshWith(t)
            }
        }
    }

    private fun ParamRow.matches(f: ParamFilter): Boolean {
        if (f.group != null && group != f.group) return false
        if (f.modifiedOnly && !modified) return false
        if (!f.showAdvanced && meta?.advanced == true && f.query.isBlank()) return false
        if (f.query.isBlank()) return true
        val q = f.query.trim()
        return name.contains(q, ignoreCase = true) ||
            meta?.humanName?.contains(q, ignoreCase = true) == true ||
            meta?.description?.contains(q, ignoreCase = true) == true
    }

    fun setFilter(transform: (ParamFilter) -> ParamFilter) = filter.update(transform)

    fun refresh() {
        val t = target.value ?: return
        viewModelScope.launch { refreshWith(t) }
    }

    private suspend fun refreshWith(t: ConfigureTarget) {
        loadError.value = null
        when (val r = t.params.refresh()) {
            is ParamLoadResult.Complete -> Unit
            is ParamLoadResult.Incomplete -> _message.value = ParamMessage.LoadIncomplete(r.missing)
            is ParamLoadResult.Failed -> loadError.value = r.reason
        }
    }

    /** Validates editor input; an armed vehicle parks the write until [confirmPendingWrite]. */
    fun requestWrite(name: String, input: String) {
        val meta = metadata.value?.get(name)
        when (val v = ParamValidator.validate(meta, input)) {
            is ParamValidation.Invalid -> _message.value = ParamMessage.Invalid(name, v.reason)
            is ParamValidation.Valid -> {
                val isArmed = (state.value as? ParametersUiState.Content)?.armed == true
                if (isArmed) _pending.value = PendingWrite(name, v.value) else write(name, v.value)
            }
        }
    }

    fun confirmPendingWrite() {
        val p = _pending.value ?: return
        _pending.value = null
        write(p.name, p.value)
    }

    fun dismissPendingWrite() {
        _pending.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun write(name: String, value: Double) {
        viewModelScope.launch { _message.value = writeOne(name, value) }
    }

    private suspend fun writeOne(name: String, value: Double): ParamMessage {
        val t = target.value ?: return ParamMessage.WriteFailed(name, ParamWriteFailure.NoLink)
        return when (val r = t.params.set(name, value)) {
            is ParamSetResult.Confirmed -> ParamMessage.Saved(name, r.value.value, metadata.value?.get(name)?.rebootRequired == true)
            is ParamSetResult.Mismatch -> ParamMessage.Clamped(name, r.requested, r.actual.value)
            ParamSetResult.UnknownParam -> ParamMessage.WriteFailed(name, ParamWriteFailure.UnknownParam)
            ParamSetResult.Timeout -> ParamMessage.WriteFailed(name, ParamWriteFailure.Timeout)
            is ParamSetResult.Failed -> ParamMessage.WriteFailed(name, ParamWriteFailure.Other)
        }
    }

    /** Current values as a parameter file. */
    fun exportText(format: ParamFileFormat): String? {
        val t = target.value ?: return null
        val values = t.params.snapshot.value.values.values.map { ParamFileEntry(it.name, it.value, it.type) }
        if (values.isEmpty()) return null
        return ParamFile.write(values, format)
    }

    /** Parses a parameter file and shows how it differs from the vehicle. */
    fun importText(text: String) {
        val entries = ParamFile.parse(text)
        if (entries.isEmpty()) {
            _message.value = ParamMessage.FileUnreadable("no parameters")
            return
        }
        val current = target.value?.params?.snapshot?.value?.values?.mapValues { it.value.value } ?: emptyMap()
        _import.value = ImportReview(ParamFile.compare(current, entries))
    }

    fun dismissImport() {
        _import.value = null
    }

    /**
     * Writes every changed value from the reviewed file, one confirmed write at a time.
     * Refused while the vehicle is armed (that path is [confirmArmedImport], behind the
     * hold confirm); a vehicle that arms part-way stops the batch at the next value.
     */
    fun applyImport() {
        if (isArmed()) return
        runImport(armedConfirmed = false)
    }

    /** [applyImport] for an armed vehicle, after the operator held the confirm. */
    fun confirmArmedImport() = runImport(armedConfirmed = true)

    /** The newest heartbeat's armed flag, read at the moment of each write. */
    private fun isArmed(): Boolean = target.value?.vehicle?.telemetry?.heartbeat?.value?.armed == true

    private fun runImport(armedConfirmed: Boolean) {
        val review = _import.value ?: return
        if (review.running) return
        viewModelScope.launch {
            _import.update { it?.copy(running = true, applied = 0, failed = emptyList()) }
            for (diff in review.comparison.changed) {
                if (!armedConfirmed && isArmed()) break
                val ok = writeOne(diff.name, diff.fromFile) is ParamMessage.Saved
                _import.update { r ->
                    r?.copy(applied = r.applied + if (ok) 1 else 0, failed = if (ok) r.failed else r.failed + diff.name)
                }
            }
            _import.update { it?.copy(running = false) }
        }
    }
}
