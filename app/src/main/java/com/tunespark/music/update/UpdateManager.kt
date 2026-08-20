package com.tunespark.music.update

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

object UpdateManager {
    private const val PREFS_NAME = "tunespark_update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_update_check_time"
    private const val KEY_LAST_KNOWN_VERSION = "last_known_latest_version"
    private const val COOLDOWN_MILLIS = 24 * 60 * 60 * 1000L // 24 hours

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var downloadJob: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getLastCheckTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_CHECK_TIME, 0L)
    }

    private fun saveLastCheckTime(context: Context, time: Long) {
        getPrefs(context).edit().putLong(KEY_LAST_CHECK_TIME, time).apply()
    }

    /**
     * Checks for updates on app startup if the 24-hour cooldown has elapsed.
     * If an update is available, posts an Android notification to open the Updates screen.
     * Does NOT display any popups inside the app.
     */
    fun checkOnStartup(context: Context) {
        val lastCheck = getLastCheckTime(context)
        val now = System.currentTimeMillis()
        if (now - lastCheck < COOLDOWN_MILLIS) {
            return
        }

        scope.launch(Dispatchers.IO) {
            val result = UpdateChecker.checkLatestRelease(context)
            saveLastCheckTime(context, System.currentTimeMillis())

            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    // Check if already downloaded
                    val downloadedApk = UpdateDownloader.getDownloadedApk(context, result.releaseInfo)
                    if (downloadedApk != null) {
                        _state.value = UpdateState.Downloaded(result.releaseInfo, downloadedApk)
                    } else {
                        _state.value = UpdateState.UpdateAvailable(result.releaseInfo, result.currentVersion)
                    }
                    // Show system notification
                    UpdateNotificationHelper.showUpdateNotification(context, result.releaseInfo)
                }
                is UpdateCheckResult.UpToDate -> {
                    _state.value = UpdateState.UpToDate(result.currentVersion, result.latestVersion)
                }
                is UpdateCheckResult.NoApkFound -> {
                    _state.value = UpdateState.UpToDate(result.currentVersion, result.releaseInfo.versionName)
                }
                is UpdateCheckResult.Error -> {
                    // Silent on startup failure
                }
            }
        }
    }

    /**
     * Explicitly checks for updates, bypassing the 24-hour cooldown.
     */
    fun checkForUpdates(context: Context) {
        _state.value = UpdateState.Checking
        scope.launch(Dispatchers.IO) {
            val result = UpdateChecker.checkLatestRelease(context)
            saveLastCheckTime(context, System.currentTimeMillis())

            when (result) {
                is UpdateCheckResult.UpdateAvailable -> {
                    val downloadedApk = UpdateDownloader.getDownloadedApk(context, result.releaseInfo)
                    if (downloadedApk != null) {
                        _state.value = UpdateState.Downloaded(result.releaseInfo, downloadedApk)
                    } else {
                        _state.value = UpdateState.UpdateAvailable(result.releaseInfo, result.currentVersion)
                    }
                }
                is UpdateCheckResult.UpToDate -> {
                    _state.value = UpdateState.UpToDate(result.currentVersion, result.latestVersion)
                }
                is UpdateCheckResult.NoApkFound -> {
                    _state.value = UpdateState.Error(
                        message = "New release found (${result.releaseInfo.tagName}), but no APK asset was attached.",
                        releaseInfo = result.releaseInfo
                    )
                }
                is UpdateCheckResult.Error -> {
                    _state.value = UpdateState.Error(
                        message = result.message
                    )
                }
            }
        }
    }

    /**
     * Starts downloading the APK. Called only upon explicit user action.
     */
    fun startDownload(context: Context, releaseInfo: ReleaseInfo) {
        downloadJob?.cancel()
        _state.value = UpdateState.Downloading(
            releaseInfo = releaseInfo,
            bytesDownloaded = 0L,
            totalBytes = releaseInfo.apkSizeBytes,
            progress = 0f
        )

        downloadJob = scope.launch(Dispatchers.IO) {
            val downloadResult = UpdateDownloader.downloadApk(
                context = context,
                releaseInfo = releaseInfo,
                onProgress = { bytesDownloaded, totalBytes, progress ->
                    _state.value = UpdateState.Downloading(
                        releaseInfo = releaseInfo,
                        bytesDownloaded = bytesDownloaded,
                        totalBytes = totalBytes,
                        progress = progress
                    )
                }
            )

            downloadResult.fold(
                onSuccess = { apkFile ->
                    _state.value = UpdateState.Downloaded(releaseInfo, apkFile)
                    // Launch installer
                    UpdateInstaller.installApk(context, apkFile)
                },
                onFailure = { error ->
                    _state.value = UpdateState.Error(
                        message = error.localizedMessage ?: "Download failed.",
                        releaseInfo = releaseInfo
                    )
                }
            )
        }
    }

    /**
     * Cancels the active download and deletes the partial download file.
     */
    fun cancelDownload(context: Context) {
        val currentState = _state.value
        val releaseInfo = when (currentState) {
            is UpdateState.Downloading -> currentState.releaseInfo
            is UpdateState.Error -> currentState.releaseInfo
            else -> null
        }
        UpdateDownloader.cancelDownload(context, releaseInfo)
        downloadJob?.cancel()
        downloadJob = null

        val currentVersion = UpdateChecker.getCurrentVersionName(context)
        if (releaseInfo != null) {
            _state.value = UpdateState.UpdateAvailable(releaseInfo, currentVersion)
        } else {
            _state.value = UpdateState.Idle
        }
    }

    /**
     * Launches package installer for an already downloaded APK.
     */
    fun installDownloadedApk(context: Context, apkFile: File): InstallResult {
        return UpdateInstaller.installApk(context, apkFile)
    }

    /**
     * Helper to format bytes nicely (e.g. "24.5 MB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
