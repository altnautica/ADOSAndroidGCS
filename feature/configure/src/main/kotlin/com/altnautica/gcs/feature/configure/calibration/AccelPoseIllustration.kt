package com.altnautica.gcs.feature.configure.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.altnautica.gcs.core.designsystem.theme.AdosTheme
import com.altnautica.gcs.core.link.calibration.AccelPose

/**
 * Vehicle orientation for one accelerometer pose, drawn over a ground line. Level, nose
 * up/down and back use a side view (nose to the right, top marked with a dot); the two side
 * poses use a rear view with the arms drawn.
 */
@Composable
fun AccelPoseIllustration(pose: AccelPose, description: String, modifier: Modifier = Modifier) {
    val colors = AdosTheme.colors
    Canvas(modifier.size(160.dp).semantics { contentDescription = description }) {
        val ground = size.height * 0.9f
        drawLine(colors.fgTertiary, Offset(0f, ground), Offset(size.width, ground), strokeWidth = 2.dp.toPx())
        val body = Size(size.width * 0.5f, size.height * 0.16f)
        val center = Offset(size.width / 2f, size.height / 2f)
        val degrees = when (pose) {
            AccelPose.Level -> 0f
            AccelPose.NoseDown -> 90f
            AccelPose.NoseUp -> -90f
            AccelPose.Back -> 180f
            AccelPose.LeftSide -> -90f
            AccelPose.RightSide -> 90f
        }
        val rearView = pose == AccelPose.LeftSide || pose == AccelPose.RightSide
        rotate(degrees, center) {
            val topLeft = Offset(center.x - body.width / 2, center.y - body.height / 2)
            drawRoundRect(colors.accentPrimary, topLeft, body, CornerRadius(6.dp.toPx()))
            if (rearView) {
                // Arms and props seen from behind.
                val armY = center.y - body.height / 2
                drawLine(colors.fgSecondary, Offset(topLeft.x - body.width * 0.25f, armY), Offset(topLeft.x + body.width * 1.25f, armY), strokeWidth = 4.dp.toPx())
            } else {
                // Nose arrow on the right.
                val nose = Path().apply {
                    moveTo(topLeft.x + body.width, center.y - body.height / 2)
                    lineTo(topLeft.x + body.width + body.height, center.y)
                    lineTo(topLeft.x + body.width, center.y + body.height / 2)
                    close()
                }
                drawPath(nose, colors.accentPrimary)
            }
            // Top-side marker.
            drawCircle(colors.statusWarning, radius = 5.dp.toPx(), center = Offset(center.x, center.y - body.height))
        }
    }
}
