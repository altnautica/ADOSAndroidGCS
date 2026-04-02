package com.altnautica.gcs.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.altnautica.gcs.data.serial.UsbSerialManager
import com.altnautica.gcs.ui.theme.ElectricBlue
import com.altnautica.gcs.ui.theme.ErrorRed
import com.altnautica.gcs.ui.theme.OnSurfaceMedium
import com.altnautica.gcs.ui.theme.SuccessGreen
import com.altnautica.gcs.ui.theme.SurfaceVariant

/**
 * USB Serial connection settings panel. Shows detected flight controllers,
 * baud rate selector, and connect/disconnect controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbSerialSettings(
    usbSerialManager: UsbSerialManager,
    modifier: Modifier = Modifier,
) {
    val isConnected by usbSerialManager.isConnected.collectAsStateWithLifecycle()
    val connectedDevice by usbSerialManager.connectedDevice.collectAsStateWithLifecycle()
    val detectedDevices by usbSerialManager.detectedDevices.collectAsStateWithLifecycle()
    val baudRate by usbSerialManager.baudRate.collectAsStateWithLifecycle()

    var baudDropdownExpanded by remember { mutableStateOf(false) }

    // Scan on first composition
    LaunchedEffect(Unit) {
        usbSerialManager.scanDevices()
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "USB Serial",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))

                // Connection status badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.Usb else Icons.Filled.UsbOff,
                        contentDescription = "USB status",
                        tint = if (isConnected) SuccessGreen else OnSurfaceMedium,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isConnected) "Connected" else "Disconnected",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isConnected) SuccessGreen else OnSurfaceMedium,
                    )
                }
            }

            if (isConnected && connectedDevice != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "$connectedDevice @ $baudRate",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Baud rate dropdown
            Text(
                text = "Baud Rate",
                style = MaterialTheme.typography.labelMedium,
                color = OnSurfaceMedium,
            )
            Spacer(Modifier.height(4.dp))

            ExposedDropdownMenuBox(
                expanded = baudDropdownExpanded,
                onExpandedChange = { baudDropdownExpanded = !baudDropdownExpanded },
            ) {
                TextField(
                    value = baudRate.toString(),
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = baudDropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = baudDropdownExpanded,
                    onDismissRequest = { baudDropdownExpanded = false },
                ) {
                    UsbSerialManager.BAUD_RATES.forEach { rate ->
                        DropdownMenuItem(
                            text = { Text(rate.toString()) },
                            onClick = {
                                usbSerialManager.setBaudRate(rate)
                                baudDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = OnSurfaceMedium.copy(alpha = 0.2f))
            Spacer(Modifier.height(12.dp))

            // Device list header + scan button
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Detected Devices",
                    style = MaterialTheme.typography.labelMedium,
                    color = OnSurfaceMedium,
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { usbSerialManager.scanDevices() },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Rescan USB devices",
                        tint = ElectricBlue,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (detectedDevices.isEmpty()) {
                Text(
                    text = "No USB serial devices found. Connect a flight controller and tap refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMedium,
                )
            } else {
                detectedDevices.forEach { device ->
                    DeviceRow(
                        deviceName = device.name,
                        vidPid = "VID:0x%04X PID:0x%04X".format(device.vendorId, device.productId),
                        isFc = device.isFc,
                        isConnected = isConnected && connectedDevice == device.name,
                        onConnect = {
                            if (isConnected) {
                                usbSerialManager.disconnect()
                            } else {
                                usbSerialManager.connect(device)
                            }
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }

            // Disconnect button when connected
            if (isConnected) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { usbSerialManager.disconnect() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Disconnect", color = ErrorRed)
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    deviceName: String,
    vidPid: String,
    isFc: Boolean,
    isConnected: Boolean,
    onConnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isFc) {
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = ElectricBlue.copy(alpha = 0.2f),
                    ) {
                        Text(
                            text = "FC",
                            style = MaterialTheme.typography.labelSmall,
                            color = ElectricBlue,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            Text(
                text = vidPid,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMedium,
            )
        }

        Button(
            onClick = onConnect,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isConnected) ErrorRed else ElectricBlue,
            ),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text(if (isConnected) "Disconnect" else "Connect")
        }
    }
}
