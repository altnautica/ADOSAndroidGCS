package com.altnautica.gcs.feature.configure.params

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.link.param.ParamLoadResult
import com.altnautica.gcs.core.link.param.ParamSetResult
import com.altnautica.gcs.core.link.param.ParamSnapshot
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.feature.configure.common.ConfigureTarget
import com.altnautica.gcs.feature.configure.common.ConfigureTargetSource
import com.altnautica.gcs.feature.configure.common.LINK_FRESH_MS
import com.altnautica.gcs.feature.configure.common.fcVariant
import com.altnautica.gcs.feature.configure.common.secondTicks
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ParametersViewModel.Factory::class)
class ParametersViewModel internal constructor(
    nodeId: DeviceId,
    targets: ConfigureTargetSource,
    private val now: () -> Long,
    ticks: Flow<Unit>,
    private val metadataFor: suspend (String) -> ParamMetadataSet?,
) : ViewModel() {
    @AssistedInject constructor(
        @Assisted nodeId: String,
        targets: ConfigureTargetSource,
        metadata: ParamMetadataRepository,
    ) : this(DeviceId(nodeId), targets, SystemClock::elapsedRealtime, secondTicks(), metadata::load)

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

    private val _writing = MutableStateFlow<String?>(null)

    /** The parameter whose write is waiting for the flight controller's echo, or null. */
    val writing: StateFlow<String?> = _writing.asStateFlow()

    private val _import = MutableStateFlow<ImportReview?>(null)
    val importReview: StateFlow<ImportReview?> = _import.asStateFlow()

    private val _rebootPending = MutableStateFlow(false)

    /** A confirmed write needs a flight-controller reboot before it takes effect. */
    val rebootPending: StateFlow<Boolean> = _rebootPending.asStateFlow()
    private var rebooting = false
    private var applyJob: Job? = null

    private val heartbeat: Flow<HeartbeatState?> = target.flatMapLatest { it?.vehicle?.telemetry?.heartbeat ?: flowOf(null) }

    private val metadata: StateFlow<ParamMetadataSet?> = heartbeat
        .map { hb -> hb?.let { ParamMetadataKey.forVehicle(it.fcVariant(), it.vehicleType) } }
        .distinctUntilChanged()
        .mapLatest { key -> key?.let { metadataFor(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val snapshot: Flow<Pair<ParamSnapshot, Boolean>> = target.flatMapLatest { t ->
        t?.let { combine(it.params.snapshot, it.params.loading) { s, l -> s to l } } ?: flowOf(ParamSnapshot.EMPTY to false)
    }

    private data class VehicleInfo(val armed: Boolean, val mavType: Int?, val fresh: Boolean)

    private val vehicleInfo: Flow<VehicleInfo> = combine(heartbeat, ticks) { hb, _ ->
        VehicleInfo(hb?.armed == true, hb?.vehicleType, hb != null && now() - hb.tMs <= LINK_FRESH_MS)
    }.distinctUntilChanged()

    val state: StateFlow<ParametersUiState> = combine(
        combine(target, snapshot, loadError) { t, s, e -> Triple(t, s, e) },
        metadata,
        filter,
        vehicleInfo,
    ) { (t, snap, error), meta, f, info ->
        val (s, loading) = snap
        when {
            t == null -> ParametersUiState.NoVehicle
            // Values the FC broadcasts on its own (a changed parameter) are not a loaded list.
            !s.complete && error != null -> ParametersUiState.LoadFailed(error)
            !s.complete && (loading || s.values.isEmpty()) -> ParametersUiState.Loading(s.received, s.count)
            else -> {
                val all = s.values.values.map { ParamRow(it.name, it.value, it.type, meta?.get(it.name)) }
                ParametersUiState.Content(
                    rows = all.filter { it.matches(f) }.sortedWith(compareBy({ it.group }, { it.name })),
                    byName = all.associateBy { it.name },
                    total = all.size,
                    filter = f,
                    armed = info.armed,
                    mavType = info.mavType,
                    hasMetadata = meta != null,
                    loading = loading,
                    linkFresh = info.fresh,
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

    /**
     * Validates editor input. An armed vehicle, or one whose arm state is unknown (stale link),
     * parks the write until [confirmPendingWrite]. A write already in flight ignores a second one.
     */
    fun requestWrite(name: String, input: String) {
        if (_writing.value != null) return
        val meta = metadata.value?.get(name)
        when (val v = ParamValidator.validate(name, meta, input)) {
            is ParamValidation.Invalid -> _message.value = ParamMessage.Invalid(name, v.reason)
            is ParamValidation.Valid -> {
                val content = state.value as? ParametersUiState.Content
                when {
                    content?.armed == true -> _pending.value = PendingWrite(name, v.value)
                    content != null && !content.linkFresh -> _pending.value = PendingWrite(name, v.value, armUnknown = true)
                    else -> write(name, v.value)
                }
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
        _writing.value = name
        viewModelScope.launch {
            try {
                _message.value = writeOne(name, value)
            } finally {
                _writing.value = null
            }
        }
    }

    private suspend fun writeOne(name: String, value: Double): ParamMessage {
        val t = target.value ?: return ParamMessage.WriteFailed(name, ParamWriteFailure.NoLink)
        val result = when (val r = t.params.set(name, value)) {
            is ParamSetResult.Confirmed -> ParamMessage.Saved(name, r.value.value, metadata.value?.get(name)?.rebootRequired == true)
            is ParamSetResult.Mismatch -> ParamMessage.Clamped(name, r.requested, r.actual.value)
            ParamSetResult.UnknownParam -> ParamMessage.WriteFailed(name, ParamWriteFailure.UnknownParam)
            ParamSetResult.Timeout -> ParamMessage.WriteFailed(name, ParamWriteFailure.Timeout)
            is ParamSetResult.Failed -> ParamMessage.WriteFailed(name, ParamWriteFailure.Other)
        }
        if (result is ParamMessage.Saved && result.rebootRequired) _rebootPending.value = true
        return result
    }

    /** A change made outside the parameter list (a calibration) applies after a reboot. */
    fun markRebootNeeded() {
        _rebootPending.value = true
    }

    /**
     * Reboots the flight controller so pending changes apply. Only while disarmed on a fresh
     * link; an accepted reboot clears the pending flag.
     */
    fun reboot() {
        val c = state.value as? ParametersUiState.Content ?: return
        val t = target.value ?: return
        if (rebooting || c.armed || !c.linkFresh || isArmed()) return
        rebooting = true
        viewModelScope.launch {
            val result = t.vehicle.control.run(VehicleCommand.RebootAutopilot)
            if (result == CommandResult.Accepted) _rebootPending.value = false
            _message.value = ParamMessage.Reboot(result)
            rebooting = false
        }
    }

    /** Reports the outcome of writing a parameter file chosen by the operator. */
    fun fileWritten(result: Result<Unit>) {
        _message.value = result.fold({ ParamMessage.FileSaved }, { ParamMessage.FileWriteFailed })
    }

    /** A chosen parameter file could not be read. */
    fun fileUnreadable(reason: String?) {
        _message.value = ParamMessage.FileUnreadable(reason.orEmpty())
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
        val comparison = ParamFile.compare(current, entries)
        // A file row gets the same checks as a single edit; a refused row starts unticked and stays so.
        val meta = metadata.value
        val invalid = comparison.changed.mapNotNull { d ->
            (ParamValidator.validate(d.name, meta?.get(d.name), d.fromFile) as? ParamValidation.Invalid)?.let { d.name to it.reason }
        }.toMap()
        _import.value = ImportReview(comparison, invalid = invalid)
    }

    /**
     * Closes the review. A running apply is cancelled, so no further value is sent, and the
     * count confirmed so far is reported.
     */
    fun dismissImport() {
        val review = _import.value
        applyJob?.cancel()
        applyJob = null
        _import.value = null
        if (review != null && review.running) _message.value = ParamMessage.ImportStopped(review.applied, review.selected.size)
    }

    /** Includes or leaves out one changed row of the reviewed file; ignored while applying and for refused rows. */
    fun toggleImportRow(name: String) = _import.update { r ->
        if (r == null || r.running || name in r.invalid) r else r.copy(deselected = if (name in r.deselected) r.deselected - name else r.deselected + name)
    }

    /**
     * Writes every selected changed value from the reviewed file, one confirmed write at a
     * time. Refused while the vehicle is armed; a vehicle that arms part-way stops the batch
     * at the next value.
     */
    fun applyImport() {
        val review = _import.value ?: return
        if (review.running || isArmed()) return
        applyJob = viewModelScope.launch {
            _import.update { it?.copy(running = true, applied = 0, failed = emptyList()) }
            for (diff in review.selected) {
                if (isArmed()) break
                val ok = writeOne(diff.name, diff.fromFile) is ParamMessage.Saved
                _import.update { r ->
                    r?.copy(applied = r.applied + if (ok) 1 else 0, failed = if (ok) r.failed else r.failed + diff.name)
                }
            }
            _import.update { it?.copy(running = false) }
            applyJob = null
        }
    }

    /** The newest heartbeat's armed flag, read at the moment of each write. */
    private fun isArmed(): Boolean = target.value?.vehicle?.telemetry?.heartbeat?.value?.armed == true
}
