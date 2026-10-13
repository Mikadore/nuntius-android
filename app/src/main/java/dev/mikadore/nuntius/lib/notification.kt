package dev.mikadore.nuntius.lib

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.mikadore.nuntius.R
import androidx.core.net.toUri

object UrgentNotifications {
    const val CHANNEL_ID = "nuntius_urgent_v2"
    private const val SOUND_CHANNEL_ID = "nuntius_sound_alert_v1"

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
                "android.resource://${context.packageName}/${R.raw.alarm_alert}".toUri(),
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

    @SuppressLint("MissingPermission")
    fun showSound(
        context: Context,
        title: String,
        message: String,
        deleteIntent: PendingIntent,
        notificationId: Int,
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

        createSoundChannel(appContext)
        val notificationManager = NotificationManagerCompat.from(appContext)
        if (!notificationManager.areNotificationsEnabled()) return false
        if (
            appContext.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(SOUND_CHANNEL_ID)
                ?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            return false
        }

        val notification = NotificationCompat.Builder(appContext, SOUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(title)
            .setContentText(message)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDeleteIntent(deleteIntent)
            .build()

        notificationManager.notify(notificationId, notification)
        return true
    }

    private fun createSoundChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            SOUND_CHANNEL_ID,
            "Gateway sound alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Silent popup alerts for gateway audio"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private const val DEFAULT_NOTIFICATION_ID = 1001
}
