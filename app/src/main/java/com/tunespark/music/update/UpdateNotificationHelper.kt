package com.tunespark.music.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tunespark.music.MainActivity
import com.tunespark.music.R

object UpdateNotificationHelper {
    const val CHANNEL_ID = "tunespark_app_updates"
    private const val NOTIFICATION_ID = 1001

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications about new Tunespark releases and updates"
                enableLights(true)
                setShowBadge(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    fun showUpdateNotification(context: Context, releaseInfo: ReleaseInfo) {
        createNotificationChannel(context)

        // On Android 13+ (API 33), check POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.tunespark.music.action.SHOW_UPDATES"
            putExtra("open_screen", "UPDATES")
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Update Available: Tunespark ${releaseInfo.tagName}")
            .setContentText("Version ${releaseInfo.versionName} is ready to download. Tap to view release notes.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("A new version of Tunespark (${releaseInfo.tagName}) is available. Tap here to view release notes and download the update.")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Handled safely if permission is denied
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelNotification(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
