package com.altnautica.gcs.feature.cockpit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.feature.cockpit.chrome.ChromePanel
import com.altnautica.gcs.feature.cockpit.chrome.ChromeText
import com.altnautica.gcs.feature.cockpit.chrome.FlightSizes
import com.altnautica.gcs.feature.cockpit.chrome.GimbalPad
import com.altnautica.gcs.feature.cockpit.chrome.PhotoVideoSwitch
import com.altnautica.gcs.feature.cockpit.chrome.RoundButton
import com.altnautica.gcs.feature.cockpit.chrome.Shutter
import com.altnautica.gcs.feature.cockpit.chrome.ZoomPill
import com.altnautica.gcs.feature.cockpit.flow.specText
import com.altnautica.gcs.feature.cockpit.tips.tipAnchor

/** Shutter, photo/video switch, zoom (with the digital zoom in use beside it), the gimbal pad when it fits here, camera settings. */
@Composable
internal fun RightColumn(
    vm: CockpitViewModel,
    sizes: FlightSizes,
    gimbalInColumn: Boolean,
    zoomInColumn: Boolean,
    onCameraSettings: () -> Unit,
) {
    val recording by vm.recordingActive.collectAsStateWithLifecycle()
    val canRecord by vm.canRecord.collectAsStateWithLifecycle()
    val zoom by vm.digitalZoom.collectAsStateWithLifecycle()
    var videoMode by rememberSaveable { mutableStateOf(false) }
    val video = videoMode && canRecord
    RightColumnLayout(
        zoom = zoom,
        zoomInColumn = zoomInColumn,
        gap = sizes.rightGap,
        top = {
            Shutter(
                video = video,
                recording = recording,
                label = stringResource(if (video) R.string.grid_record else R.string.grid_shutter),
                size = sizes.shutter,
                onClick = { if (video) vm.toggleRecording() else vm.capturePhoto() },
            )
            PhotoVideoSwitch(
                video = video,
                videoEnabled = canRecord,
                photoLabel = stringResource(R.string.grid_mode_photo),
                videoLabel = stringResource(R.string.grid_mode_video),
                onVideo = { videoMode = it },
                width = sizes.shutter,
                height = sizes.modeSwitch,
            )
        },
        zoomPill = {
            ZoomPill(
                inLabel = specText("cockpit.zoom.in"),
                outLabel = specText("cockpit.zoom.out"),
                onIn = vm::zoomIn,
                onOut = vm::zoomOut,
                onLongPress = null,
                width = sizes.rightButton,
                height = sizes.zoom,
            )
        },
        bottom = {
            if (gimbalInColumn) Pad(vm, sizes.gimbalPad)
            RoundButton(Icons.Outlined.Tune, specText("cockpit.camera.settings"), sizes.rightButton, "grid-camera-settings", onCameraSettings)
        },
    )
}

/**
 * The right column's layout: [top], the zoom pill when it fits the column ([zoomInColumn]),
 * then [bottom]. The digital zoom in use ([zoom] above 1) is named on the video side, beside
 * the pill or, when the pill dropped out of the column, at the column's top, clear of a gimbal
 * pad sitting beside the column's foot; the badge takes no room in the column.
 */
@Composable
internal fun RightColumnLayout(
    zoom: Int,
    zoomInColumn: Boolean,
    gap: Dp,
    top: @Composable ColumnScope.() -> Unit,
    zoomPill: @Composable () -> Unit,
    bottom: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            top()
            if (zoomInColumn) {
                Box {
                    zoomPill()
                    ZoomBadge(zoom, Modifier.align(Alignment.CenterStart), centred = true)
                }
            }
            bottom()
        }
        if (!zoomInColumn) ZoomBadge(zoom, Modifier.align(Alignment.TopStart), centred = false)
    }
}

/** "Digital ×N", hung off the left of [modifier]'s anchor point, [centred] on it or below it; nothing at 1×. */
@Composable
private fun ZoomBadge(zoom: Int, modifier: Modifier, centred: Boolean) {
    if (zoom <= 1) return
    ChromePanel(
        modifier
            .layout { measurable, _ ->
                val p = measurable.measure(Constraints())
                layout(0, 0) { p.place(-p.width - 8.dp.roundToPx(), if (centred) -p.height / 2 else 0) }
            }
            .testTag("digital-zoom"),
    ) {
        ChromeText(specText("camera.digital_zoom", "n" to zoom.toString()), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
internal fun Pad(vm: CockpitViewModel, size: Dp) {
    GimbalPad(
        label = specText("cockpit.gimbal_pad"),
        hint = specText("cockpit.gimbal_pad.hint"),
        size = size,
        onRate = vm.gimbal::deflect,
        onRecenter = vm::recenterGimbal,
        modifier = Modifier.tipAnchor("gimbal_pad"),
    )
}
