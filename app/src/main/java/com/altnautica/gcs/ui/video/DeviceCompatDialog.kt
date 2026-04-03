package com.altnautica.gcs.ui.video

import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign

/**
 * Checks SoC manufacturer and shows a compatibility warning for Mode B (direct USB WFB-ng).
 *
 * Mode B relies on the devourer userspace USB driver which has been primarily tested on
 * Qualcomm Snapdragon chipsets. MediaTek and Exynos may work but are not guaranteed.
 */
object DeviceCompat {
    enum class SocTier {
        /** Qualcomm Snapdragon. Best tested, full support. */
        QUALCOMM,
        /** MediaTek. Likely works, less tested. */
        MEDIATEK,
        /** Samsung Exynos. May have USB host quirks. */
        EXYNOS,
        /** Unknown SoC. Warn user. */
        UNKNOWN,
    }

    fun detectSocTier(): SocTier {
        val soc = Build.SOC_MANUFACTURER.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()

        return when {
            soc.contains("qualcomm") || hardware.contains("qcom") -> SocTier.QUALCOMM
            soc.contains("mediatek") || hardware.contains("mt") -> SocTier.MEDIATEK
            soc.contains("samsung") || hardware.contains("exynos") -> SocTier.EXYNOS
            // Fallback: check board for common patterns
            board.contains("sdm") || board.contains("sm") -> SocTier.QUALCOMM
            board.contains("mt") -> SocTier.MEDIATEK
            else -> SocTier.UNKNOWN
        }
    }

    fun needsWarning(): Boolean {
        return detectSocTier() != SocTier.QUALCOMM
    }

    fun warningMessage(): String {
        val tier = detectSocTier()
        val socName = Build.SOC_MANUFACTURER.ifBlank { Build.HARDWARE }
        return when (tier) {
            SocTier.QUALCOMM -> "" // No warning needed
            SocTier.MEDIATEK ->
                "Your device uses a MediaTek chipset ($socName). " +
                "Mode B (direct USB video) has been mainly tested on Qualcomm devices. " +
                "It should work on MediaTek but you may see issues with some adapters."
            SocTier.EXYNOS ->
                "Your device uses an Exynos chipset ($socName). " +
                "Mode B (direct USB video) has been mainly tested on Qualcomm devices. " +
                "Exynos USB host support varies by model and may not work reliably."
            SocTier.UNKNOWN ->
                "Your device chipset ($socName) has not been tested with Mode B " +
                "(direct USB video). It may or may not work. Qualcomm Snapdragon " +
                "devices have the best compatibility."
        }
    }
}

@Composable
fun DeviceCompatDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Device Compatibility",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text = DeviceCompat.warningMessage(),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("Continue Anyway")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
