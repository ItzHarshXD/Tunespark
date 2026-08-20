package com.tunespark.music.update

import android.content.Context
import android.content.Intent
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
