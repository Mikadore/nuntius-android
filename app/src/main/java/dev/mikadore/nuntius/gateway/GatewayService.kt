package dev.mikadore.nuntius.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.mikadore.nuntius.AuthenticatedApp
import dev.mikadore.nuntius.R
import dev.mikadore.nuntius.lib.UrgentNotifications
import dev.mikadore.nuntius.network.AuthSession
import dev.mikadore.nuntius.repos.AuthState
import dev.nuntius.gateway.proto.NotifySound
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GatewayService : Service() {
    @Inject
    lateinit var gateway: GatewayConnection

    @Inject
    lateinit var eventCollector: GatewayEventCollector

    @Inject
    lateinit var authSession: AuthSession

    @Inject
    lateinit var logger: GatewayLogger

    private var serviceScope = newServiceScope()
    private var explicitlyStopped = false
    private var observersStarted = false
    private var activePlayer: MediaPlayer? = null
    private var activePlaybackGeneration: Int? = null
    private var playbackGeneration = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            logger.info("Notification stop action received.")
            stopByUser(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISMISS_SOUND) {
            stopNotifySound(intent.getIntExtra(EXTRA_PLAYBACK_GENERATION, INVALID_GENERATION))
            if (!observersStarted) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            return START_STICKY
        }

        explicitlyStopped = false
        if (serviceScope.coroutineContext[Job]?.isActive != true) {
            serviceScope = newServiceScope()
            observersStarted = false
        }
        logger.info(
            if (intent == null) {
                "Gateway service started for sticky recovery (null restart intent)."
            } else {
                "Gateway service start requested by authenticated app."
            },
        )
        createNotificationChannel()
        promoteToForeground(buildNotification(GatewayState.Connecting))
        eventCollector.start(::handleNotifySound)
        gateway.connect()
        if (!observersStarted) {
            observersStarted = true
            serviceScope.launch {
                gateway.state.collect { state -> updateNotification(state) }
            }
            serviceScope.launch {
                authSession.state.collect { authState ->
                    if (authState == AuthState.LoggedOut) stopForMissingAuthentication()
                }
            }
        }
        // A null intent is the normal START_STICKY recovery path after process termination.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        logger.info("Gateway service destroyed; cancelling gateway connection.")
        serviceScope.cancel()
        stopNotifySound()
        eventCollector.stop()
        gateway.disconnect()
        super.onDestroy()
    }

    private fun stopByUser(startId: Int) {
        if (explicitlyStopped) return
        explicitlyStopped = true
        logger.info("Gateway explicitly stopped by user; automatic service restart disabled.")
        serviceScope.cancel()
        observersStarted = false
        stopNotifySound()
        eventCollector.stop()
        gateway.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun stopForMissingAuthentication() {
        logger.info("Authentication removed; stopping gateway service.")
        serviceScope.cancel()
        observersStarted = false
        stopNotifySound()
        eventCollector.stop()
        gateway.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        logger.info("Gateway service promoted to foreground.")
    }

    private fun updateNotification(state: GatewayState) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(state))
        } catch (_: SecurityException) {
            // Android 13+ can hide notifications when the user denies notification permission.
            // The service remains foreground and Android still lists it under Active apps.
            logger.warn("Notification update was denied by Android notification permission.")
        }
    }

    private fun handleNotifySound(notifySound: NotifySound) {
        val audio = notifySound.audio.toByteArray()
        serviceScope.launch {
            playNotifySound(notifySound.title, notifySound.summary, audio)
        }
    }

    private fun playNotifySound(title: String, summary: String, audio: ByteArray) {
        if (audio.isEmpty()) {
            logger.warn("Received a gateway notify_sound message with no audio data.")
            return
        }

        val speaker = findBuiltInSpeaker()
        if (speaker == null) {
            logger.warn("No built-in speaker output is available for gateway audio playback.")
            return
        }

        stopNotifySound()
        val generation = ++playbackGeneration
        activePlaybackGeneration = generation
        try {
            UrgentNotifications.showSound(
                context = this,
                title = title,
                message = summary,
                deleteIntent = createDismissSoundIntent(generation),
                notificationId = SOUND_NOTIFICATION_ID,
            )
        } catch (_: SecurityException) {
            logger.warn("Gateway sound notification was denied by Android notification permission.")
        }

        val player = try {
            MediaPlayer()
        } catch (_: Exception) {
            finishNotifySound(generation, null, failed = true)
            return
        }
        activePlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(ByteArrayMediaDataSource(audio))
            if (!player.setPreferredDevice(speaker)) {
                finishNotifySound(generation, player, failed = true)
                return
            }
            player.setVolume(1f, 1f)
            player.setOnPreparedListener { preparedPlayer ->
                if (activePlaybackGeneration == generation && activePlayer === preparedPlayer) {
                    preparedPlayer.start()
                }
            }
            player.setOnCompletionListener { completedPlayer ->
                finishNotifySound(generation, completedPlayer, failed = false)
            }
            player.setOnErrorListener { failedPlayer, _, _ ->
                finishNotifySound(generation, failedPlayer, failed = true)
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            finishNotifySound(generation, player, failed = true)
        }
    }

    private fun findBuiltInSpeaker(): AudioDeviceInfo? {
        val outputs = getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE }
            } else {
                null
            }
    }

    private fun createDismissSoundIntent(generation: Int): PendingIntent {
        val intent = Intent(this, GatewayService::class.java)
            .setAction(ACTION_DISMISS_SOUND)
            .putExtra(EXTRA_PLAYBACK_GENERATION, generation)
        return PendingIntent.getService(
            this,
            generation,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun finishNotifySound(generation: Int, player: MediaPlayer?, failed: Boolean) {
        if (activePlaybackGeneration != generation) return
        if (player != null && activePlayer !== player) return
        activePlaybackGeneration = null
        activePlayer = null
        player?.release()
        getSystemService(NotificationManager::class.java).cancel(SOUND_NOTIFICATION_ID)
        if (failed) {
            logger.warn("Gateway notification audio could not be played.")
        } else {
            logger.info("Gateway notification audio playback completed.")
        }
    }

    private fun stopNotifySound(expectedGeneration: Int? = null) {
        if (expectedGeneration != null && activePlaybackGeneration != expectedGeneration) return
        activePlaybackGeneration = null
        activePlayer?.release()
        activePlayer = null
        getSystemService(NotificationManager::class.java).cancel(SOUND_NOTIFICATION_ID)
    }

    private fun buildNotification(state: GatewayState): Notification {
        val stopIntent = Intent(this, GatewayService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, AuthenticatedApp::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.gateway_notification_title))
            .setContentText(state.notificationText())
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_stat_gateway,
                getString(R.string.gateway_notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    private fun GatewayState.notificationText(): String = when (this) {
        GatewayState.Disconnected -> getString(R.string.gateway_status_disconnected)
        GatewayState.Connecting -> getString(R.string.gateway_status_connecting)
        GatewayState.Connected -> getString(R.string.gateway_status_connected)
        is GatewayState.Reconnecting -> getString(R.string.gateway_status_reconnecting)
        is GatewayState.Error -> getString(R.string.gateway_status_error)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gateway_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.gateway_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_STOP = "dev.mikadore.nuntius.gateway.STOP"
        private const val ACTION_DISMISS_SOUND = "dev.mikadore.nuntius.gateway.DISMISS_SOUND"
        private const val EXTRA_PLAYBACK_GENERATION = "playback_generation"
        private const val CHANNEL_ID = "gateway_connection"
        private const val NOTIFICATION_ID = 4107
        private const val SOUND_NOTIFICATION_ID = 4110
        private const val STOP_REQUEST_CODE = 4108
        private const val OPEN_REQUEST_CODE = 4109
        private const val INVALID_GENERATION = -1

        private fun newServiceScope() =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = data.size.toLong()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= data.size) return -1
        if (offset < 0 || size < 0 || offset > buffer.size - size) return -1
        val start = position.toInt()
        val bytesToCopy = minOf(size, data.size - start)
        data.copyInto(
            buffer,
            destinationOffset = offset,
            startIndex = start,
            endIndex = start + bytesToCopy,
        )
        return bytesToCopy
    }

    override fun close() = Unit
}
