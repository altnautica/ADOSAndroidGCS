package com.altnautica.gcs.ui.groundstation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.ui.theme.SurfaceDark
import kotlinx.coroutines.delay

private const val BUFFER_SIZE = 60

private val GridColor = Color(0xFF2A2A35)
private val GoodColor = Color(0xFF00C853)
private val WarnColor = Color(0xFFFF8C00)
private val BadColor = Color(0xFFFF4444)
private val LabelColor = Color(0xFFA0A0A0)

/**
 * 60-second rolling line chart showing packet loss percentage.
 *
 * Color-coded segments: green (<5%), orange (5-20%), red (>20%).
 * Draws horizontal grid lines at 0%, 25%, 50%, 75%, 100%.
 */
@Composable
fun PacketLossGraph(
    currentPacketLoss: Float,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()

    // Ring buffer state
    var buffer by remember { mutableStateOf(FloatArray(BUFFER_SIZE) { 0f }) }
    var writeIndex by remember { mutableStateOf(0) }

    // Poll at 1Hz and append to buffer
    LaunchedEffect(Unit) {
        while (true) {
            val newBuffer = buffer.copyOf()
            newBuffer[writeIndex % BUFFER_SIZE] = currentPacketLoss
            buffer = newBuffer
            writeIndex++
            delay(1000)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(120.dp),
    ) {
        val w = size.width
        val h = size.height
        val rightMargin = 48f
        val bottomMargin = 18f
        val graphW = w - rightMargin
        val graphH = h - bottomMargin

        // Background
        drawRect(color = SurfaceDark)

        // Horizontal grid lines at 0%, 25%, 50%, 75%, 100%
        val gridPercents = listOf(0f, 25f, 50f, 75f, 100f)
        for (pct in gridPercents) {
            val y = graphH - (pct / 100f) * graphH
            drawLine(
                color = GridColor,
                start = Offset(0f, y),
                end = Offset(graphW, y),
                strokeWidth = 1f,
            )
        }

        // Build ordered data from ring buffer (oldest to newest)
        val totalWritten = writeIndex.coerceAtMost(BUFFER_SIZE)
        val ordered = FloatArray(totalWritten)
        for (i in 0 until totalWritten) {
            val idx = if (writeIndex >= BUFFER_SIZE) {
                (writeIndex - totalWritten + i) % BUFFER_SIZE
            } else {
                i
            }
            ordered[i] = buffer[idx]
        }

        // Draw data line segments with color coding
        if (ordered.size >= 2) {
            val stepX = graphW / (BUFFER_SIZE - 1).toFloat()
            val offsetX = (BUFFER_SIZE - ordered.size).toFloat() * stepX

            for (i in 0 until ordered.size - 1) {
                val x1 = offsetX + i * stepX
                val x2 = offsetX + (i + 1) * stepX
                val v1 = ordered[i].coerceIn(0f, 100f)
                val v2 = ordered[i + 1].coerceIn(0f, 100f)
                val y1 = graphH - (v1 / 100f) * graphH
                val y2 = graphH - (v2 / 100f) * graphH
                val midVal = (v1 + v2) / 2f
                val color = segmentColor(midVal)

                drawLine(
                    color = color,
                    start = Offset(x1, y1),
                    end = Offset(x2, y2),
                    strokeWidth = 2f,
                )
            }
        }

        // Right edge label: current value
        val currentVal = if (ordered.isNotEmpty()) ordered.last() else 0f
        drawLabel(
            textMeasurer = textMeasurer,
            text = "%.1f%%".format(currentVal),
            x = graphW + 4f,
            y = graphH / 2f - 6f,
            color = segmentColor(currentVal),
        )

        // Bottom labels
        drawLabel(
            textMeasurer = textMeasurer,
            text = "60s ago",
            x = 0f,
            y = graphH + 2f,
            color = LabelColor,
        )
        drawLabel(
            textMeasurer = textMeasurer,
            text = "now",
            x = graphW - 24f,
            y = graphH + 2f,
            color = LabelColor,
        )
    }
}

private fun segmentColor(value: Float): Color = when {
    value < 5f -> GoodColor
    value <= 20f -> WarnColor
    else -> BadColor
}

private fun DrawScope.drawLabel(
    textMeasurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    color: Color,
) {
    val style = TextStyle(
        color = color,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
    )
    val result = textMeasurer.measure(text, style)
    drawText(result, topLeft = Offset(x, y))
}
