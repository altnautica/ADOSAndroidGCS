package com.altnautica.gcs.ui.alerts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.altnautica.gcs.data.alerts.AlertEntry
import com.altnautica.gcs.data.alerts.Severity
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.SurfaceDark
import com.altnautica.gcs.ui.theme.SurfaceVariant
import com.altnautica.gcs.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertDrawer(
    alerts: List<AlertEntry>,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Auto-scroll to top (newest) when a new alert arrives
    LaunchedEffect(alerts.firstOrNull()?.timestamp) {
        if (alerts.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(SurfaceDark)
    ) {
        // Header bar (always visible, tap to expand/collapse)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = "Alerts",
                tint = if (alerts.any { it.alert.severity == Severity.CRITICAL }) ErrorRed
                       else ElectricBlue,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Alerts",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (alerts.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(
                            if (alerts.any { it.alert.severity == Severity.CRITICAL }) ErrorRed
                            else ElectricBlue
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (alerts.size > 99) "99+" else alerts.size.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (expanded && alerts.isNotEmpty()) {
                IconButton(
                    onClick = onClearAll,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear all alerts",
                        tint = Color(0xFFA0A0A0),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown
                             else Icons.Default.KeyboardArrowUp,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Color(0xFFA0A0A0),
                modifier = Modifier.size(18.dp),
            )
        }

        // Expandable alert list
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            if (alerts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No alerts",
                        color = Color(0xFFA0A0A0),
                        fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(
                        items = alerts,
                        key = { it.timestamp },
                    ) { entry ->
                        AlertRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertRow(entry: AlertEntry) {
    val severityColor = when (entry.alert.severity) {
        Severity.CRITICAL -> ErrorRed
        Severity.WARNING -> WarningAmber
        Severity.INFO -> ElectricBlue
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Severity dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(severityColor),
        )
        Spacer(modifier = Modifier.width(8.dp))

        // Message
        Text(
            text = entry.alert.message,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Timestamp
        Text(
            text = formatAlertTime(entry.timestamp),
            color = Color(0xFF707070),
            fontSize = 10.sp,
        )
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun formatAlertTime(timestamp: Long): String {
    return timeFormat.format(Date(timestamp))
}
