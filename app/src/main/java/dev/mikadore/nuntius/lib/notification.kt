package dev.mikadore.nuntius.lib

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mikadore.nuntius.R

object UrgentNotifications {
    const val CHANNEL_ID = "nuntius_urgent_v2"

    fun createChannel(context: Context) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Urgent alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Time-sensitive notifications"
            setSound(
                Uri.parse("android.resource://${context.packageName}/${R.raw.alarm_alert}"),
                attributes,
            )
            enableVibration(true)
        }

        context.applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    fun show(
        context: Context,
        title: String,
        message: String,
        notificationId: Int = DEFAULT_NOTIFICATION_ID,
    ): Boolean {
        val appContext = context.applicationContext
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        createChannel(appContext)
        if (
            appContext.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
                ?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return false
        }

        val notificationManager = NotificationManagerCompat.from(appContext)
        if (!notificationManager.areNotificationsEnabled()) return false

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationId, notification)
        return true
    }

    private const val DEFAULT_NOTIFICATION_ID = 1001
}
