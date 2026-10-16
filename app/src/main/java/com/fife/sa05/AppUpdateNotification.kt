package com.fife.sa05

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

fun appUpdateNotificationTitle(): String = "Доступно обновление SA05"

fun appUpdateNotificationContentText(versionName: String): String =
    "Доступна новая версия $versionName"

fun shouldNotifyAppUpdate(lastNotifiedVersion: String?, availableRelease: AppRelease): Boolean =
    lastNotifiedVersion != availableRelease.versionName

object AppUpdateNotification {
    const val CHANNEL_ID = "app_updates"
    const val NOTIFICATION_ID = 20

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновления приложения",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о доступных обновлениях SA05"
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    fun notifyUpdateAvailable(context: Context, release: AppRelease) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }
        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            return
        }

        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.NAVIGATE_TO_SETTINGS)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = appUpdateNotificationTitle()
        val content = appUpdateNotificationContentText(release.versionName)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_tile)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                R.drawable.ic_refresh,
                "Обновить",
                pendingIntent
            )

        if (release.notes.isNotBlank()) {
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .setBigContentTitle("$title (${release.versionName})")
                    .bigText(release.notes)
            )
        }

        try {
            notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Android 13 permission check race or revocation
        }
    }

    fun notifyIfNew(context: Context, release: AppRelease) {
        val lastNotified = AppUpdatePreferences.getLastNotifiedVersion(context)
        if (shouldNotifyAppUpdate(lastNotified, release)) {
            notifyUpdateAvailable(context, release)
            AppUpdatePreferences.setLastNotifiedVersion(context, release.versionName)
        }
        AppUpdatePreferences.saveLastAvailableRelease(context, release)
    }

    fun onUpToDate(context: Context) {
        cancel(context)
        AppUpdatePreferences.clearLastNotifiedVersion(context)
        AppUpdatePreferences.clearLastAvailableRelease(context)
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}
