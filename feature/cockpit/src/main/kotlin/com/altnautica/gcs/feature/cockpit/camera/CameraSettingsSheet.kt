package com.altnautica.gcs.feature.cockpit.camera

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.altnautica.gcs.core.designsystem.component.AdosButton
import com.altnautica.gcs.core.designsystem.component.AdosButtonVariant
import com.altnautica.gcs.core.designsystem.component.SelectSettingRow
import com.altnautica.gcs.core.designsystem.component.SwitchSettingRow
import com.altnautica.gcs.core.designsystem.component.overlay.AdosSheet
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.designsystem.tokens.AdosSpacing
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.feature.cockpit.R
import com.altnautica.gcs.feature.cockpit.flow.specText

/** What the camera-settings sheet shows. */
internal data class CameraSheetUi(
    /** The vehicle's live streams: each camera and each quality it offers. */
    val streams: List<StreamDescriptor>,
    val selectedStreamId: String?,
    /** False while no video plays: the sheet says so and offers no stream or recording. */
    val hasVideo: Boolean,
    val digitalZoom: Int,
    val canRecord: Boolean,
    val recording: Boolean,
    val pipAvailable: Boolean,
    val pipOn: Boolean,
)

/**
 * Camera settings over the flight screen (the right column's camera-settings button):
 * the camera and stream to show, picture-in-picture, the digital zoom in use and
 * recording. A sheet like every other, so it starts below the top bar and closes with Back.
 */
@Composable
internal fun CameraSettingsSheet(
    ui: CameraSheetUi,
    onDismiss: () -> Unit,
    onSelectStream: (String) -> Unit,
    onTogglePip: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    AdosSheet(onDismiss = onDismiss, title = specText("cockpit.camera.settings"), modifier = Modifier.testTag("camera-sheet")) {
        if (!ui.hasVideo) {
            Text(
                specText("camera.no_video"),
                color = AdosTheme.colors.fgSecondary,
                modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2).testTag("camera-sheet-no-video"),
            )
        }
        val streams = ui.streams.filter { it.live }
        if (ui.hasVideo && streams.isNotEmpty()) {
            SelectSettingRow(
                title = specText("cockpit.menu.cameras"),
                options = streams,
                selected = streams.firstOrNull { it.id == ui.selectedStreamId } ?: streams.first(),
                optionLabel = ::streamLabel,
                onSelect = { onSelectStream(it.id) },
                enabled = streams.size > 1,
                modifier = Modifier.testTag("camera-sheet-stream"),
            )
        }
        if (ui.pipAvailable) SwitchSettingRow(stringResource(R.string.cockpit_qs_pip), ui.pipOn, { onTogglePip() })
        if (ui.hasVideo) {
            // The zoom in use: the camera's own, or the digital zoom of the shown video.
            val zoom = if (ui.digitalZoom > 1) specText("camera.digital_zoom", "n" to ui.digitalZoom.toString()) else "1×"
            Text(
                specText("cockpit.zoom") + " · " + zoom,
                color = AdosTheme.colors.fgPrimary,
                modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2).testTag("camera-sheet-zoom"),
            )
            AdosButton(
                label = specText(if (ui.recording) "cockpit.camera.stop_recording" else "cockpit.camera.start_recording"),
                onClick = onToggleRecording,
                variant = if (ui.recording) AdosButtonVariant.Danger else AdosButtonVariant.Secondary,
                enabled = ui.canRecord,
                modifier = Modifier.padding(horizontal = AdosSpacing.S4, vertical = AdosSpacing.S2).testTag("camera-sheet-record"),
            )
            if (!ui.canRecord) {
                Text(
                    specText("cockpit.camera.no_extension"),
                    color = AdosTheme.colors.fgSecondary,
                    modifier = Modifier.padding(horizontal = AdosSpacing.S4).testTag("camera-sheet-record-unavailable"),
                )
            }
        }
    }
}

/** A stream's name in the camera list: its role (the camera) and its codec (the quality leg). */
internal fun streamLabel(s: StreamDescriptor): String {
    val name = s.role.ifBlank { s.id }
    return if (s.codec.isBlank()) name else "$name · ${s.codec.uppercase()}"
}
