package dev.mikadore.nuntius.gateway

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mikadore.nuntius.lib.UrgentNotifications
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import dev.nuntius.gateway.proto.GatewayMessage
import dev.nuntius.gateway.proto.Greeting
import dev.nuntius.gateway.proto.Notification
import dev.nuntius.gateway.proto.NotifySound

/**
 * Application-scoped consumer for gateway messages. Add event handling in the payload-specific
 * methods below; this collector is started and stopped with [GatewayService].
 */
@Singleton
class GatewayEventCollector @Inject constructor(
    private val gateway: GatewayConnection,
    private val logger: GatewayLogger,
    @ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectionJob: Job? = null

    /** Starts consuming messages once. The subscription is established before this returns. */
    fun start(onNotifySound: (NotifySound) -> Unit) {
        synchronized(lock) {
            if (collectionJob?.isActive == true) {
                logger.debug("Gateway event collector is already running.")
                return
            }

            logger.info("Starting gateway event collector.")
            collectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                gateway.events.collect { event ->
                    try {
                        handleEvent(event, onNotifySound)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        logger.warn("Gateway event handling failed; collector remains active.")
                    }
                }
            }
        }
    }

    /** Stops consuming messages. A later service start can safely start it again. */
    fun stop() {
        val job = synchronized(lock) {
            collectionJob.also { collectionJob = null }
        }
        if (job != null) {
            logger.info("Stopping gateway event collector.")
            job.cancel()
        }
    }

    private suspend fun handleEvent(
        event: GatewayEvent,
        onNotifySound: (NotifySound) -> Unit,
    ) {
        when (event) {
            is GatewayEvent.Message -> handleMessage(event.value, onNotifySound)
        }
    }

    private suspend fun handleMessage(
        message: GatewayMessage,
        onNotifySound: (NotifySound) -> Unit,
    ) {
        when (message.payloadCase) {
            GatewayMessage.PayloadCase.GREETING -> handleGreeting(message.greeting)
            GatewayMessage.PayloadCase.NOTIFICATION -> handleNotification(message.notification)
            GatewayMessage.PayloadCase.NOTIFY_SOUND -> handleNotifySound(message.notifySound, onNotifySound)
            GatewayMessage.PayloadCase.PAYLOAD_NOT_SET -> {
                logger.warn("Received a GatewayMessage with no recognized payload.")
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun handleGreeting(greeting: Greeting) {
        // Add greeting-specific behavior here. Keep message contents out of logs.
        logger.debug("Consumed a gateway greeting message.")
    }

    private suspend fun handleNotifySound(
        notifySound: NotifySound,
        onNotifySound: (NotifySound) -> Unit,
    ) {
        logger.info("Received a gateway notify_sound message.")
        onNotifySound(notifySound)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun handleNotification(notification: Notification) {
        logger.info("Consumed a gateway notification message.")
        UrgentNotifications.show(
	        context = context,
            title = notification.title,
            message = notification.message,
        )
    }
}
