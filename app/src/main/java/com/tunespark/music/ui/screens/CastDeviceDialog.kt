package com.tunespark.music.ui.screens

import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.mediarouter.media.MediaRouter
import com.tunespark.music.cast.CastDevice
import com.tunespark.music.cast.CastManager

/**
 * Premium Cast device picker.
 *
 * Opens from the Radio screen's cast button, starts Cast discovery, lists every
 * Cast-capable device on the local network, and connects to the tapped one.
 * Once the session starts the dialog closes and the Radio button turns red.
 */
@Composable
fun CastDeviceDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return

    val context = LocalContext.current
    val view = LocalView.current
    val isDark = isSystemInDarkTheme()
    val audioManager = remember(context) {
        context.getSystemService(AudioManager::class.java)
    }

    val surfaceColor = if (isDark) Color(0xFF16161A) else Color(0xFFF2F2F5)
    val onSurface = if (isDark) Color.White else Color(0xFF0B0B0C)
    val muted = onSurface.copy(alpha = 0.6f)
    val hairline = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    val accent = Color(0xFFFF0000)

    val click: () -> Unit = {
        audioManager?.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    DisposableEffect(Unit) {
        CastManager.startDiscovery(context)
        onDispose { CastManager.stopDiscovery() }
    }

    // Close the picker only when a session actually starts *after* it opened.
    // Opening the picker while already casting keeps the connected card visible
    // so the user can disconnect.
    var wasCasting by remember { mutableStateOf(CastManager.isCasting) }
    LaunchedEffect(CastManager.isCasting) {
        val casting = CastManager.isCasting
        if (casting && !wasCasting) {
            val name = CastManager.connectedDeviceName
            Toast.makeText(
                context,
                if (name != null) "Casting to $name" else "Casting",
                Toast.LENGTH_SHORT
            ).show()
            onDismiss()
        }
        wasCasting = casting
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = surfaceColor,
            border = BorderStroke(1.dp, hairline),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (CastManager.isCasting) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                        contentDescription = null,
                        tint = if (CastManager.isCasting) accent else onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cast",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = onSurface
                        )
                        Text(
                            text = if (CastManager.isCasting) "Connected" else "Choose a device",
                            fontSize = 12.sp,
                            color = muted
                        )
                    }
                    IconButton(onClick = { click(); onDismiss() }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!CastManager.isAvailable) {
                    Text(
                        text = "Casting isn't available on this device. Make sure Google Play services is installed and up to date.",
                        fontSize = 13.sp,
                        color = muted,
                        lineHeight = 19.sp
                    )
                    return@Column
                }

                if (CastManager.isCasting) {
                    ConnectedDeviceCard(
                        deviceName = CastManager.connectedDeviceName ?: "Cast device",
                        surface = if (isDark) Color(0xFF232327) else Color(0xFFE6E6EA),
                        onSurface = onSurface,
                        accent = accent,
                        onDisconnect = {
                            click()
                            CastManager.disconnect()
                            Toast.makeText(context, "Stopped casting", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Available devices",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = muted,
                            modifier = Modifier.weight(1f)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = muted.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Searching…", fontSize = 11.sp, color = muted)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (CastManager.devices.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No devices found",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Make sure your phone and your TV, speaker or display are on the same Wi-Fi network.",
                                fontSize = 12.sp,
                                color = muted,
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(CastManager.devices, key = { it.id }) { device ->
                                DeviceRow(
                                    device = device,
                                    isConnecting = device.id == CastManager.connectingDeviceId,
                                    onClick = {
                                        click()
                                        CastManager.connect(device.id)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Chromecast and Google Cast speakers",
                    fontSize = 11.sp,
                    color = muted.copy(alpha = 0.8f),
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun ConnectedDeviceCard(
    deviceName: String,
    surface: Color,
    onSurface: Color,
    accent: Color,
    onDisconnect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(surface, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.CastConnected,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = deviceName,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(accent, RoundedCornerShape(14.dp))
                .clickable(onClick = onDisconnect)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Disconnect",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: CastDevice,
    isConnecting: Boolean,
    onClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val onSurface = if (isDark) Color.White else Color(0xFF0B0B0C)
    val accent = Color(0xFFFF0000)
    val rowSurface = if (isDark) Color(0xFF232327) else Color(0xFFE9E9EE)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowSurface, RoundedCornerShape(16.dp))
            .clickable(enabled = !CastManager.isConnecting, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = iconForDevice(device.deviceType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name.ifBlank { "Cast device" },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (isConnecting) {
                Text(
                    text = "Connecting…",
                    fontSize = 11.sp,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun iconForDevice(deviceType: Int): ImageVector = when (deviceType) {
    MediaRouter.RouteInfo.DEVICE_TYPE_TV -> Icons.Rounded.Tv
    MediaRouter.RouteInfo.DEVICE_TYPE_SPEAKER -> Icons.Rounded.Speaker
    MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> Icons.Rounded.SpeakerGroup
    else -> Icons.Rounded.Cast
}
