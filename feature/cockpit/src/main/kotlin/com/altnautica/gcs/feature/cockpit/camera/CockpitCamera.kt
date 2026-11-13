package com.altnautica.gcs.feature.cockpit.camera

import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleControl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One shutter press. The screen copies the shown frame when it receives this, at the press,
 * and saves the copy only once [keep] answers true: the vehicle's camera did not take the photo.
 */
class FrameGrab(val keep: Deferred<Boolean>)

/**
 * The flight screen's shutter and zoom. The vehicle's camera shoots and zooms when it can;
 * otherwise the shown frame is saved on the device and the video zooms digitally.
 */
internal class CockpitCamera(private val scope: CoroutineScope, private val control: () -> VehicleControl?) {
    private val _digitalZoom = MutableStateFlow(1)

    /**
     * Digital zoom of the shown video (1, 2 or 4) once the vehicle's camera refused a zoom
     * command; label `camera.digital_zoom`. Stays 1 while the camera zooms itself.
     */
    val digitalZoom: StateFlow<Int> = _digitalZoom.asStateFlow()

    private val _frameGrabs = MutableSharedFlow<FrameGrab>(extraBufferCapacity = GRAB_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every shutter press, handed out at the press so the screen copies the frame the operator saw. */
    val frameGrabs: SharedFlow<FrameGrab> = _frameGrabs.asSharedFlow()

    /** The camera zoom exchange in flight; presses during it are ignored. */
    private var zoomJob: Job? = null

    /** Asks the vehicle's camera for a photo and hands out the frame grab at once. */
    fun capturePhoto() {
        val c = control()
        val keep: Deferred<Boolean> = if (c == null) CompletableDeferred(true) else scope.async { !c.run(VehicleCommand.CapturePhoto).cameraTookIt() }
        _frameGrabs.tryEmit(FrameGrab(keep))
    }

    /**
     * The vehicle's camera zooms when it can: a press zooms continuously for [ZOOM_BURST_MS] and
     * then stops it (rate 0). Otherwise the video zooms digitally 1× → 2× → 4×. The stop goes out
     * whatever happened to the start (refused, lost ack, timeout, no link) and when the screen
     * closes mid-press, so a camera that did start never runs to its limit.
     */
    fun zoom(step: Int) {
        if (zoomJob?.isActive == true) return
        val c = control()
        if (_digitalZoom.value > 1 || c == null) {
            _digitalZoom.update { digitalZoomStep(it, step) }
            return
        }
        zoomJob = scope.launch {
            try {
                if (c.run(VehicleCommand.CameraZoom(step.coerceIn(-1, 1))).cameraTookIt()) {
                    delay(ZOOM_BURST_MS)
                } else {
                    _digitalZoom.update { digitalZoomStep(it, step) }
                }
            } finally {
                withContext(NonCancellable) { c.run(VehicleCommand.CameraZoom(0)) }
            }
        }
    }

    private companion object {
        /** Quick presses queue while the screen is still copying the previous frame. */
        const val GRAB_BUFFER = 4

        /** How long one press zooms the vehicle's camera before the stop. */
        const val ZOOM_BURST_MS = 400L
    }
}

/** The camera accepted (or queued) the command itself. */
internal fun CommandResult.cameraTookIt(): Boolean = this == CommandResult.Accepted || this is CommandResult.Queued

/** The next digital zoom level, 1× ↔ 2× ↔ 4×. */
internal fun digitalZoomStep(current: Int, step: Int): Int {
    val levels = listOf(1, 2, 4)
    val at = levels.indexOf(current).coerceAtLeast(0)
    return levels[(at + step.coerceIn(-1, 1)).coerceIn(0, levels.lastIndex)]
}
