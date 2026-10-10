package com.altnautica.gcs.feature.logs.tlog

import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.InputStream

/**
 * Plays a telemetry log back as a read-only link: frames are emitted with their recorded
 * spacing divided by [speed]. Writes are refused, so a replayed cockpit can never command.
 * [incoming] is cold; each collection replays from the start.
 */
class TlogReplayTransport(
    private val open: () -> InputStream,
    private val speed: Float = 1f,
) : MavlinkByteTransport {
    constructor(file: File, speed: Float = 1f) : this({ file.inputStream() }, speed)

    init {
        require(speed > 0f) { "speed must be positive" }
    }

    private val _state = MutableStateFlow<TransportState>(TransportState.Idle)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    override val incoming: Flow<ByteArray> = flow {
        _state.value = TransportState.Open
        try {
            TlogReader(open()).use { reader ->
                var previousUs = -1L
                for (record in reader) {
                    if (_state.value is TransportState.Closed) break
                    if (previousUs >= 0) {
                        val gapMs = ((record.timeUs - previousUs) / 1000f / speed).toLong()
                        if (gapMs > 0) delay(gapMs.coerceAtMost(MAX_GAP_MS))
                    }
                    previousUs = record.timeUs
                    emit(record.frame)
                }
            }
            _state.value = TransportState.Closed(END_OF_LOG)
        } catch (e: TlogFormatException) {
            _state.value = TransportState.Failed(e.message.orEmpty())
        }
    }

    override suspend fun write(bytes: ByteArray): WriteResult = WriteResult.ReceiveOnly

    override suspend fun close() {
        _state.value = TransportState.Closed(null)
    }

    companion object {
        const val END_OF_LOG = "end of log"

        /** Long silences (link drops) are shortened so replay never stalls. */
        private const val MAX_GAP_MS = 2_000L
    }
}
