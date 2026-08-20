package com.tunespark.music.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val releaseInfo: ReleaseInfo,
        val currentVersion: String
    ) : UpdateCheckResult()

    data class UpToDate(
        val currentVersion: String,
        val latestVersion: String,
        val releaseInfo: ReleaseInfo? = null
    ) : UpdateCheckResult()

    data class NoApkFound(
        val currentVersion: String,
        val releaseInfo: ReleaseInfo
    ) : UpdateCheckResult()

    data class Error(
        val message: String,
        val exception: Throwable? = null
    ) : UpdateCheckResult()
}

object UpdateChecker {
    private const val GITHUB_OWNER = "ItzHarshXD"
    private const val GITHUB_REPO = "Tunespark"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Gets the installed app's versionName.
     */
    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Checks the GitHub Releases API for the latest published release.
     * Does NOT check commits.
     */
    suspend fun checkLatestRelease(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersionName(context)
        try {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Tunespark-App/$currentVersion")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val body = response.body?.string() ?: ""
                return@withContext UpdateCheckResult.Error(
                    message = "GitHub API returned HTTP $code: ${response.message.ifBlank { body }}"
                )
            }

            val bodyString = response.body?.string()
                ?: return@withContext UpdateCheckResult.Error("Empty response received from GitHub.")

            val json = JSONObject(bodyString)
            val tagName = json.optString("tag_name", "")
            val name = json.optString("name", tagName)
            val body = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")
            val htmlUrl = json.optString("html_url", "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases")
            val isDraft = json.optBoolean("draft", false)
            val isPrerelease = json.optBoolean("prerelease", false)

            if (tagName.isBlank()) {
                return@withContext UpdateCheckResult.Error("Invalid release data: missing tag_name.")
            }

            val assetsArray = json.optJSONArray("assets")
            var apkDownloadUrl = ""
            var apkFileName = ""
            var apkSizeBytes = 0L

            if (assetsArray != null) {
                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    val contentType = asset.optString("content_type", "")
                    val downloadUrl = asset.optString("browser_download_url", "")
                    val size = asset.optLong("size", 0L)

                    val isApk = assetName.endsWith(".apk", ignoreCase = true) ||
                            contentType.equals("application/vnd.android.package-archive", ignoreCase = true)

                    if (isApk && downloadUrl.isNotBlank()) {
                        apkDownloadUrl = downloadUrl
                        apkFileName = assetName.ifBlank { "Tunespark-$tagName.apk" }
                        apkSizeBytes = size
                        break
                    }
                }
            }

            val latestVersion = VersionComparator.sanitizeVersion(tagName)
            val releaseInfo = ReleaseInfo(
                tagName = tagName,
                versionName = latestVersion,
                title = name.ifBlank { "Version $tagName" },
                releaseNotes = body,
                publishedAt = publishedAt,
                htmlUrl = htmlUrl,
                apkDownloadUrl = apkDownloadUrl,
                apkFileName = apkFileName,
                apkSizeBytes = apkSizeBytes
            )

            if (apkDownloadUrl.isBlank()) {
                if (VersionComparator.isNewer(latestVersion, currentVersion)) {
                    return@withContext UpdateCheckResult.NoApkFound(
                        currentVersion = currentVersion,
                        releaseInfo = releaseInfo
                    )
                } else {
                    return@withContext UpdateCheckResult.UpToDate(
                        currentVersion = currentVersion,
                        latestVersion = latestVersion,
                        releaseInfo = releaseInfo
                    )
                }
            }

            if (VersionComparator.isNewer(latestVersion, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(
                    releaseInfo = releaseInfo,
                    currentVersion = currentVersion
                )
            } else {
                UpdateCheckResult.UpToDate(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseInfo = releaseInfo
                )
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(
                message = e.localizedMessage ?: "Failed to check for updates.",
                exception = e
            )
        }
    }
}
