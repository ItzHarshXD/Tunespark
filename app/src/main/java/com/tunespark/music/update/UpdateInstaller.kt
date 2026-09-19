package com.tunespark.music.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed class InstallResult {
    object Success : InstallResult()
    object PermissionRequired : InstallResult()
    data class Error(val message: String) : InstallResult()
}

object UpdateInstaller {

    /**
     * Checks whether the app has permission to install unknown packages (Android 8.0+).
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Returns an Intent to open the system settings screen for "Install unknown apps".
     */
    fun createManageUnknownAppSourcesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Verifies that [apkFile] is a valid, uncorrupted APK archive matching this app's package name.
     */
    fun verifyApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0) return false
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            packageInfo != null && packageInfo.packageName == context.packageName
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reads the versionCode of an APK archive without installing it.
     * Returns null if the file is not a valid APK archive for this app.
     */
    fun getApkVersionCode(context: Context, apkFile: File): Long? {
        if (!apkFile.exists() || apkFile.length() <= 0) return null
        return try {
            val pm = context.packageManager
            val packageInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
            if (packageInfo != null && packageInfo.packageName == context.packageName) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toLong()
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the currently installed app's versionCode.
     */
    fun getInstalledVersionCode(context: Context): Long {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Checks whether a cached APK is actually NEWER than the currently installed app.
     * This is critical for update caching: release assets may reuse the same filename,
     * so a stale (old-version) APK can sit in the updates folder and pass a basic
     * validity check. Requiring versionCode > installed versionCode guarantees a cached
     * file is a genuine pending update, never a re-install of the current version.
     */
    fun isApkNewerThanInstalled(context: Context, apkFile: File): Boolean {
        val cachedVersionCode = getApkVersionCode(context, apkFile) ?: return false
        return cachedVersionCode > getInstalledVersionCode(context)
    }

    /**
     * Launches Android's system package installer using the downloaded APK's FileProvider content URI.
     * The app does NOT attempt to silently install or replace itself.
     */
    fun installApk(context: Context, apkFile: File): InstallResult {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            return InstallResult.Error("APK file does not exist or is empty.")
        }

        // Verify package archive integrity
        if (!verifyApk(context, apkFile)) {
            return InstallResult.Error("Downloaded file is not a valid update package for Tunespark.")
        }

        // Check install unknown apps permission
        if (!canRequestPackageInstalls(context)) {
            return InstallResult.PermissionRequired
        }

        return try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(intent)
            InstallResult.Success
        } catch (e: Exception) {
            InstallResult.Error("Failed to launch package installer: ${e.localizedMessage}")
        }
    }
}
