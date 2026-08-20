package com.tunespark.music.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateState()
    data class UpdateAvailable(val releaseInfo: ReleaseInfo, val currentVersion: String) : UpdateState()
    data class Downloading(
        val releaseInfo: ReleaseInfo,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val progress: Float
    ) : UpdateState()
    data class Downloaded(val releaseInfo: ReleaseInfo, val apkFile: File) : UpdateState()
    data class Error(val message: String, val releaseInfo: ReleaseInfo? = null) : UpdateState()
}

object UpdateDownloader {
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private var activeDownloadJob: Job? = null
    private var activeCall: okhttp3.Call? = null

    /**
     * Returns the dedicated directory where update APKs are downloaded.
     */
    fun getUpdatesDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "updates")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Checks if a valid APK for [releaseInfo] has already been downloaded.
     */
    fun getDownloadedApk(context: Context, releaseInfo: ReleaseInfo): File? {
        val dir = getUpdatesDir(context)
        val file = File(dir, releaseInfo.apkFileName)
        return if (file.exists() && UpdateInstaller.verifyApk(context, file)) {
            file
        } else {
            null
        }
    }

    /**
     * Cancels any active download and deletes the incomplete temporary file.
     */
    fun cancelDownload(context: Context, releaseInfo: ReleaseInfo?) {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        activeCall?.cancel()
        activeCall = null

        if (releaseInfo != null) {
            val dir = getUpdatesDir(context)
            val partFile = File(dir, "${releaseInfo.apkFileName}.part")
            if (partFile.exists()) {
                partFile.delete()
            }
        }
    }

    /**
     * Downloads the APK file from GitHub release asset with progress updates.
     */
    suspend fun downloadApk(
        context: Context,
        releaseInfo: ReleaseInfo,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = getUpdatesDir(context)
        val targetFile = File(updatesDir, releaseInfo.apkFileName)
        val partFile = File(updatesDir, "${releaseInfo.apkFileName}.part")

        // If target file already exists and is valid, return it immediately
        if (targetFile.exists() && UpdateInstaller.verifyApk(context, targetFile)) {
            return@withContext Result.success(targetFile)
        }

        // Clean up previous incomplete partial download
        if (partFile.exists()) {
            partFile.delete()
        }

        val request = Request.Builder()
            .url(releaseInfo.apkDownloadUrl)
            .header("User-Agent", "Tunespark-App/${UpdateChecker.getCurrentVersionName(context)}")
            .header("Accept", "application/octet-stream")
            .build()

        val call = httpClient.newCall(request)
        activeCall = call

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                partFile.delete()
                return@withContext Result.failure(
                    Exception("Failed to download APK: HTTP ${response.code} ${response.message}")
                )
            }

            val body = response.body
                ?: return@withContext Result.failure(Exception("Download failed: empty server response."))

            val totalBytes = if (body.contentLength() > 0) body.contentLength() else releaseInfo.apkSizeBytes
            var bytesDownloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    var lastProgressUpdate = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        val now = System.currentTimeMillis()
                        // Update progress at most every 100ms or when complete
                        if (now - lastProgressUpdate > 100 || (totalBytes > 0 && bytesDownloaded >= totalBytes)) {
                            lastProgressUpdate = now
                            val progress = if (totalBytes > 0) {
                                (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            } else {
                                -1f
                            }
                            onProgress(bytesDownloaded, totalBytes, progress)
                        }
                    }
                    output.flush()
                }
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }

            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            // Verify the integrity of the downloaded APK
            if (!UpdateInstaller.verifyApk(context, targetFile)) {
                targetFile.delete()
                return@withContext Result.failure(
                    Exception("Downloaded APK verification failed: package archive is invalid or corrupted.")
                )
            }

            Result.success(targetFile)
        } catch (e: CancellationException) {
            if (partFile.exists()) {
                partFile.delete()
            }
            throw e
        } catch (e: Exception) {
            if (partFile.exists()) {
                partFile.delete()
            }
            Result.failure(e)
        } finally {
            activeCall = null
        }
    }
}
