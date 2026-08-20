package com.tunespark.music.ui.screens

import android.content.Context
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunespark.music.AppScreen
import com.tunespark.music.update.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdatesScreen(
    onNavigate: (AppScreen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val uriHandler = LocalUriHandler.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    val backgroundColor = MaterialTheme.colorScheme.background
    val textColor = MaterialTheme.colorScheme.onBackground
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val isDarkTheme = MaterialTheme.colorScheme.background == Color.Black
    val cardBgColor = if (isDarkTheme) Color(0xFF16161A) else Color(0xFFF2F2F5)
    val cardBorderColor = if (isDarkTheme) Color.White.copy(alpha = 0.12f) else Color.Black.copy(alpha = 0.08f)

    val currentVersionName = remember { UpdateChecker.getCurrentVersionName(context) }
    val updateState by UpdateManager.state.collectAsState()

    var showPermissionDialog by remember { mutableStateOf(false) }
    var pendingInstallApk by remember { mutableStateOf<File?>(null) }

    fun playClickSound() {
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK, 1f)
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // Permission request dialog
    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Text(
                    text = "Permission Required",
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )
            },
            text = {
                Text(
                    text = "To install the update over Tunespark, Android requires you to allow installing unknown apps for this app in Settings.",
                    color = textColor.copy(alpha = 0.8f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        playClickSound()
                        showPermissionDialog = false
                        val intent = UpdateInstaller.createManageUnknownAppSourcesIntent(context)
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Text("Open Settings", color = onPrimaryColor)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        playClickSound()
                        showPermissionDialog = false
                    }
                ) {
                    Text("Cancel", color = textColor.copy(alpha = 0.6f))
                }
            },
            containerColor = cardBgColor,
            shape = RoundedCornerShape(20.dp)
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SettingsHeader(title = "Updates", onBack = { onNavigate(AppScreen.SETTINGS) })

        Text(
            text = "Installed Version",
            color = textColor.copy(alpha = 0.6f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )

        Text(
            text = "v$currentVersionName",
            color = textColor,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
        )

        when (val state = updateState) {
            is UpdateState.Idle -> {
                val lastCheckTime = UpdateManager.getLastCheckTime(context)
                val formattedTime = if (lastCheckTime > 0) {
                    SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.getDefault()).format(Date(lastCheckTime))
                } else null

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = "Updates",
                            tint = primaryColor,
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Check for New Releases",
                            color = textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (formattedTime != null) {
                            Text(
                                text = "Last checked: $formattedTime",
                                color = textColor.copy(alpha = 0.5f),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                playClickSound()
                                UpdateManager.checkForUpdates(context)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(27.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = onPrimaryColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Check for updates",
                                color = onPrimaryColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            is UpdateState.Checking -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = primaryColor,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "Checking GitHub for updates...",
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Fetching latest published release",
                            color = textColor.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            is UpdateState.UpToDate -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Up to date",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(52.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "You're up to date!",
                            color = textColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Tunespark v${state.currentVersion} is currently the latest release.",
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedButton(
                            onClick = {
                                playClickSound()
                                UpdateManager.checkForUpdates(context)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
                            border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Check again",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            is UpdateState.UpdateAvailable -> {
                val release = state.releaseInfo
                val formattedSize = remember(release.apkSizeBytes) {
                    UpdateManager.formatBytes(release.apkSizeBytes)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Surface(
                                    color = Color(0xFFFF0000).copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "NEW VERSION",
                                        color = Color(0xFFFF0000),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Text(
                                    text = release.tagName,
                                    color = textColor,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }

                            if (release.apkSizeBytes > 0) {
                                Text(
                                    text = formattedSize,
                                    color = textColor.copy(alpha = 0.6f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        if (release.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "What's New",
                                color = textColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isDarkTheme) Color(0xFF0D0D0E) else Color(0xFFE8E8EC))
                                    .padding(14.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = release.releaseNotes.trim(),
                                    color = textColor.copy(alpha = 0.85f),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                playClickSound()
                                UpdateManager.startDownload(context, release)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (release.apkSizeBytes > 0) "Download Update ($formattedSize)" else "Download Update",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            is UpdateState.Downloading -> {
                val release = state.releaseInfo
                val downloadedFormatted = UpdateManager.formatBytes(state.bytesDownloaded)
                val totalFormatted = UpdateManager.formatBytes(state.totalBytes)
                val progressPercent = (state.progress * 100).toInt().coerceIn(0, 100)

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Downloading Update",
                                    color = textColor,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = release.tagName,
                                    color = textColor.copy(alpha = 0.6f),
                                    fontSize = 14.sp
                                )
                            }

                            Text(
                                text = "$progressPercent%",
                                color = primaryColor,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        if (state.progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFFFF0000),
                                trackColor = textColor.copy(alpha = 0.1f)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFFFF0000),
                                trackColor = textColor.copy(alpha = 0.1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "$downloadedFormatted / $totalFormatted",
                                color = textColor.copy(alpha = 0.55f),
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Do not close app",
                                color = textColor.copy(alpha = 0.4f),
                                fontSize = 12.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        OutlinedButton(
                            onClick = {
                                playClickSound()
                                UpdateManager.cancelDownload(context)
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor),
                            border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(24.dp)
                        ) {
                            Text(
                                text = "Cancel Download",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            is UpdateState.Downloaded -> {
                val release = state.releaseInfo
                val apkFile = state.apkFile

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(52.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Update Ready to Install",
                            color = textColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = "Version ${release.tagName} has been downloaded and verified.",
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        if (release.releaseNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 160.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isDarkTheme) Color(0xFF0D0D0E) else Color(0xFFE8E8EC))
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = release.releaseNotes.trim(),
                                    color = textColor.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(
                            onClick = {
                                playClickSound()
                                val result = UpdateManager.installDownloadedApk(context, apkFile)
                                if (result is InstallResult.PermissionRequired) {
                                    pendingInstallApk = apkFile
                                    showPermissionDialog = true
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF0000)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Install Update",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        TextButton(
                            onClick = {
                                playClickSound()
                                UpdateManager.startDownload(context, release)
                            }
                        ) {
                            Text(
                                text = "Re-download APK",
                                color = textColor.copy(alpha = 0.6f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            is UpdateState.Error -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBgColor),
                    border = BorderStroke(1.dp, cardBorderColor)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Update Check Failed",
                            color = textColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = state.message,
                            color = textColor.copy(alpha = 0.6f),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp)
                        )

                        Button(
                            onClick = {
                                playClickSound()
                                if (state.releaseInfo != null) {
                                    UpdateManager.startDownload(context, state.releaseInfo)
                                } else {
                                    UpdateManager.checkForUpdates(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = onPrimaryColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Retry",
                                color = onPrimaryColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Credits & Links Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Made with ❤️ by Harsh",
                color = textColor.copy(alpha = 0.8f),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        playClickSound()
                        uriHandler.openUri("https://linktr.ee/harshcodes")
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Star this project on GitHub",
                color = primaryColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                style = androidx.compose.ui.text.TextStyle(
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        playClickSound()
                        uriHandler.openUri("https://github.com/ItzHarshXD/Tunespark")
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(110.dp))
    }
}
