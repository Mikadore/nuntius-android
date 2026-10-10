package dev.mikadore.nuntius.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.mikadore.nuntius.AuthenticatedApp
import dev.mikadore.nuntius.R
import dev.mikadore.nuntius.network.AuthSession
import dev.mikadore.nuntius.repos.AuthState
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
    lateinit var authSession: AuthSession

    @Inject
    lateinit var logger: GatewayLogger

    private var serviceScope = newServiceScope()
    private var explicitlyStopped = false
    private var observersStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            logger.info("Notification stop action received.")
            stopByUser(startId)
            return START_NOT_STICKY
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
        gateway.disconnect()
        super.onDestroy()
    }

    private fun stopByUser(startId: Int) {
        if (explicitlyStopped) return
        explicitlyStopped = true
        logger.info("Gateway explicitly stopped by user; automatic service restart disabled.")
        serviceScope.cancel()
        observersStarted = false
        gateway.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    private fun stopForMissingAuthentication() {
        logger.info("Authentication removed; stopping gateway service.")
        serviceScope.cancel()
        observersStarted = false
        gateway.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
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
        private const val CHANNEL_ID = "gateway_connection"
        private const val NOTIFICATION_ID = 4107
        private const val STOP_REQUEST_CODE = 4108
        private const val OPEN_REQUEST_CODE = 4109

        private fun newServiceScope() =
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
