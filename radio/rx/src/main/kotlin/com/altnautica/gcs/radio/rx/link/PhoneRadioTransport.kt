package com.altnautica.gcs.radio.rx.link

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.WriteResult
import com.altnautica.gcs.radio.rx.session.PhoneRadioSession
import com.altnautica.gcs.radio.rx.session.PhoneRadioState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MAVLink from one drone over the phone radio. The phone never transmits on
 * the drone link, so every write answers [WriteResult.ReceiveOnly].
 */
class PhoneRadioTransport(
    private val node: DeviceId,
    private val session: PhoneRadioSession,
) : MavlinkByteTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<TransportState>(TransportState.Connecting)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    init {
        scope.launch {
            session.state.collect { s ->
                if (_state.value is TransportState.Closed) return@collect
                _state.value = when (s) {
                    is PhoneRadioState.Receiving -> TransportState.Open
                    PhoneRadioState.Starting -> TransportState.Connecting
                    PhoneRadioState.Stopped -> TransportState.Closed("Phone radio stopped")
                    is PhoneRadioState.Failed -> TransportState.Failed("Phone radio: ${s.reason}")
                }
            }
        }
    }

    override val incoming: Flow<ByteArray> = session.mavlink(node).takeWhile { _state.value !is TransportState.Closed }

    override suspend fun write(bytes: ByteArray): WriteResult = WriteResult.ReceiveOnly

    override suspend fun close() {
        _state.value = TransportState.Closed(null)
        scope.cancel()
    }
}

/** Opens [PhoneRadioTransport]s for the [Reach.PhoneRadio] reach. */
@Singleton
class PhoneRadioTransportProvider @Inject constructor(
    private val session: PhoneRadioSession,
) : TransportProvider {
    override fun supports(reach: Reach): Boolean = reach is Reach.PhoneRadio

    override suspend fun open(node: NodeSummary, reach: Reach): MavlinkByteTransport {
        require(reach is Reach.PhoneRadio) { "unsupported reach $reach" }
        return PhoneRadioTransport(node.id, session)
    }
}
