package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The FC status texts the cockpit holds, from the MAVLink link or relayed by the onboard pilot,
 * each classified once: the prearm reason, the critical or failsafe text, or a pill warning.
 */
class FcTexts {
    private val _critical = MutableStateFlow<ReceivedText?>(null)
    private val _prearm = MutableStateFlow<ReceivedText?>(null)
    private val _warnings = MutableStateFlow<List<ReceivedText>>(emptyList())

    val critical: StateFlow<ReceivedText?> = _critical.asStateFlow()
    val prearm: StateFlow<ReceivedText?> = _prearm.asStateFlow()

    /** Recent texts at WARNING or ERROR severity, newest last, for the status pill. */
    val warnings: StateFlow<List<ReceivedText>> = _warnings.asStateFlow()

    /**
     * One FC status text, from the link or [relayed]. The pilot relays its recent texts on every
     * frame, so a relayed text is placed by the time it was produced: a copy of a text already
     * held (same text within [DUPLICATE_TEXT_MS]) changes nothing, and an older text never
     * replaces a newer held one. Re-feeding a relay is therefore a no-op. A text once heard on
     * the link stays a link text when the pilot relays it again.
     */
    fun onStatusText(severity: Int, text: String, atMs: Long, relayed: Boolean) {
        when {
            AlertEngine.isPrearm(text) -> {
                val body = AlertEngine.prearmBody(text)
                if (!relayed || replacesHeld(_prearm.value, body, atMs)) _prearm.value = ReceivedText(body, atMs)
            }
            severity <= AlertEngine.MAV_SEVERITY_CRITICAL || AlertEngine.isFailsafeAnnouncement(severity, text) -> {
                val held = _critical.value
                if (!relayed || replacesHeld(held, text, atMs)) {
                    _critical.value = ReceivedText(text, atMs, AlertEngine.MAV_SEVERITY_CRITICAL, relayedOnly(held, text, relayed))
                }
            }
            severity <= StatusModel.MAV_SEVERITY_WARNING -> _warnings.update { list ->
                val held = list.firstOrNull { it.text == text }
                if (relayed && held != null && atMs - held.atMs <= DUPLICATE_TEXT_MS) list
                else (list.filter { it.text != text } + ReceivedText(text, atMs, severity, relayedOnly(held, text, relayed)))
                    .sortedBy { it.atMs }.takeLast(StatusModel.WARNING_TEXT_MAX)
            }
        }
    }

    private fun relayedOnly(held: ReceivedText?, text: String, relayed: Boolean): Boolean =
        relayed && !(held != null && held.text == text && !held.relayed)

    /** True when a relayed [text] produced at [atMs] replaces [held]: it is newer, and not a copy of the held text. */
    private fun replacesHeld(held: ReceivedText?, text: String, atMs: Long): Boolean =
        held == null || (atMs > held.atMs && !(held.text == text && atMs - held.atMs <= DUPLICATE_TEXT_MS))

    companion object {
        /** A text seen again on the other path within this window is the same message. */
        const val DUPLICATE_TEXT_MS = 5_000L
    }
}
