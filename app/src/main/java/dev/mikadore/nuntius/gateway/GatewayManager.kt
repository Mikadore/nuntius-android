package dev.mikadore.nuntius.gateway

import com.google.protobuf.InvalidProtocolBufferException
import dev.mikadore.nuntius.network.AuthSession
import dev.mikadore.nuntius.network.InvalidServerUrlException
import dev.mikadore.nuntius.repos.AuthState
import dev.mikadore.nuntius.repos.AuthUser
import dev.nuntius.gateway.proto.GatewayMessage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

@Singleton
class GatewayManager @Inject constructor(
    private val authSession: AuthSession,
    private val webSocketFactory: WebSocket.Factory,
    private val networkMonitor: GatewayNetworkMonitor,
    private val logger: GatewayLogger,
) : GatewayConnection {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow<GatewayState>(GatewayState.Disconnected)
    override val state: StateFlow<GatewayState> = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<GatewayEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<GatewayEvent> = mutableEvents.asSharedFlow()

    private var connectionJob: Job? = null
    private var generation = 0L
    private var activeAttempt: SocketAttempt? = null

    override fun connect() {
        synchronized(lock) {
            if (connectionJob?.isActive == true && mutableState.value !is GatewayState.Error) {
                logger.debug("Connect ignored; a gateway session is already active.")
                return
            }
            connectionJob?.cancel()
            activeAttempt?.webSocket?.cancel()
            activeAttempt = null
            val runGeneration = ++generation
            logger.info("Starting authenticated gateway session monitoring.")
            logStateChange(mutableState.value, GatewayState.Disconnected)
            mutableState.value = GatewayState.Disconnected
            connectionJob = scope.launch {
                authSession.state.collectLatest { authState ->
                    when (authState) {
                        AuthState.Loading -> {
                            logger.debug("Waiting for authenticated session state.")
                            setState(runGeneration, GatewayState.Disconnected)
                        }
                        AuthState.LoggedOut -> {
                            logger.info("No authenticated session; closing gateway connection.")
                            closeActiveAttempt(runGeneration)
                            setState(runGeneration, GatewayState.Disconnected)
                        }
                        is AuthState.LoggedIn -> {
                            logger.info("Authenticated session available; connecting gateway.")
                            runAuthenticatedSession(runGeneration, authState.user)
                        }
                    }
                }
            }
        }
    }

    override fun disconnect() {
        val jobToCancel: Job?
        synchronized(lock) {
            logger.info("Disconnect requested; cancelling gateway connection and retries.")
            generation++
            jobToCancel = connectionJob
            connectionJob = null
            activeAttempt?.webSocket?.cancel()
            activeAttempt = null
            logStateChange(mutableState.value, GatewayState.Disconnected)
            mutableState.value = GatewayState.Disconnected
        }
        jobToCancel?.cancel()
    }

    override fun sendText(message: String): Boolean = synchronized(lock) {
        if (mutableState.value != GatewayState.Connected) {
            logger.debug("Text frame not sent because the gateway is not connected.")
            return@synchronized false
        }
        val accepted = activeAttempt?.webSocket?.send(message) == true
        logger.debug("Outbound text frame queued (characters=${message.length}, accepted=$accepted).")
        accepted
    }

    override fun sendBinary(message: ByteArray): Boolean = synchronized(lock) {
        if (mutableState.value != GatewayState.Connected) {
            logger.debug("Binary frame not sent because the gateway is not connected.")
            return@synchronized false
        }
        val accepted = activeAttempt?.webSocket?.send(ByteString.of(*message)) == true
        logger.debug("Outbound binary frame queued (bytes=${message.size}, accepted=$accepted).")
        accepted
    }

    private suspend fun runAuthenticatedSession(runGeneration: Long, user: AuthUser) {
        var failedAttempts = 0
        while (currentCoroutineContext().isActive && isCurrentUser(runGeneration, user)) {
            if (!networkMonitor.hasValidatedInternet.value) {
                logger.warn("No validated internet connection; waiting before retrying gateway.")
                setState(
                    runGeneration,
                    GatewayState.Reconnecting(
                        attempt = failedAttempts,
                        retryInMillis = null,
                        reason = "Waiting for a validated internet connection",
                    ),
                )
                networkMonitor.hasValidatedInternet.first { it }
                logger.info("Validated internet connection is available again.")
                continue
            }

            setState(
                runGeneration,
                if (failedAttempts == 0) {
                    GatewayState.Connecting
                } else {
                    GatewayState.Reconnecting(
                        attempt = failedAttempts,
                        retryInMillis = null,
                        reason = "Connecting again",
                    )
                },
            )

            val result = try {
                connectOnce(runGeneration, user)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: InvalidServerUrlException) {
                logger.warn("Gateway connection stopped because the configured server URL is invalid.")
                setState(runGeneration, GatewayState.Error("The configured server URL is invalid"))
                return
            } catch (_: IOException) {
                SocketResult.Failed(statusCode = null)
            } catch (_: Exception) {
                SocketResult.Failed(statusCode = null)
            }

            when (result) {
                SocketResult.AuthenticationChanged -> return
                is SocketResult.Failed -> {
                    if (result.statusCode == HTTP_UNAUTHORIZED ||
                        result.statusCode == HTTP_FORBIDDEN
                    ) {
                        logger.warn(
                            "Gateway handshake rejected authentication (HTTP ${result.statusCode}); " +
                                "see bearer-interceptor diagnostics to confirm header attachment.",
                        )
                        setState(
                            runGeneration,
                            GatewayState.Error("The server rejected the gateway credentials"),
                        )
                        return
                    }
                    if (result.statusCode == HTTP_NOT_FOUND ||
                        result.statusCode == HTTP_METHOD_NOT_ALLOWED
                    ) {
                        logger.warn(
                            "Gateway endpoint was not found or does not accept WebSocket upgrades " +
                                "(HTTP ${result.statusCode}).",
                        )
                        setState(
                            runGeneration,
                            GatewayState.Error(
                                "The server does not provide the configured WebSocket gateway endpoint",
                            ),
                        )
                        return
                    }
                }
                is SocketResult.Closed -> {
                    logger.info("Gateway socket closed after ${result.connectedForMillis} ms.")
                    if (result.connectedForMillis >= STABLE_CONNECTION_MILLIS) {
                        logger.info("Connection was stable; resetting reconnect backoff.")
                        failedAttempts = 0
                    }
                }
            }

            if (!currentCoroutineContext().isActive || !isCurrentUser(runGeneration, user)) return
            val retryAttempt = ++failedAttempts
            val retryDelayMillis = GatewayRetryPolicy.delayMillis(
                attempt = retryAttempt,
                jitter = Random.nextDouble(),
            )
            logger.warn(
                "Scheduling gateway reconnect attempt $retryAttempt in ${retryDelayMillis} ms.",
            )
            setState(
                runGeneration,
                GatewayState.Reconnecting(
                    attempt = retryAttempt,
                    retryInMillis = retryDelayMillis,
                    reason = "The gateway connection closed",
                ),
            )
            delay(retryDelayMillis)
        }
    }

    private suspend fun connectOnce(runGeneration: Long, user: AuthUser): SocketResult {
        if (!isCurrentUser(runGeneration, user)) return SocketResult.AuthenticationChanged

        val request = Request.Builder()
            .url(GatewayEndpoint.fromServerBaseUrl(user.serverUrl))
            .build()
        logger.info("Opening WebSocket connection to the authenticated server gateway.")
        val attempt = SocketAttempt()
        synchronized(lock) {
            if (generation != runGeneration) return SocketResult.AuthenticationChanged
            activeAttempt?.webSocket?.cancel()
            activeAttempt = attempt
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                logger.info("WebSocket upgrade succeeded (HTTP ${response.code()}).")
                attempt.handshake.complete(HandshakeResult.Opened)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                logger.warn(
                    "Discarding inbound WebSocket text frame; gateway messages must be binary " +
                        "protobuf (characters=${text.length}).",
                )
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (!isCurrentUser(runGeneration, user)) return

                val message = try {
                    GatewayMessage.parseFrom(bytes.toByteArray())
                } catch (_: InvalidProtocolBufferException) {
                    logger.warn(
                        "Discarding malformed binary WebSocket frame; expected GatewayMessage " +
                            "protobuf (bytes=${bytes.size}).",
                    )
                    return
                }

                val hasConsumer = mutableEvents.subscriptionCount.value > 0
                mutableEvents.tryEmit(GatewayEvent.Message(message))
                logger.debug(
                    "Inbound GatewayMessage parsed (bytes=${bytes.size}, " +
                        "payload=${message.payloadCase}, consumer=$hasConsumer).",
                )
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Reply to the server's close frame without exposing its reason to app state/logs.
                logger.info("Server initiated WebSocket close (code=$code).")
                webSocket.close(code, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                logger.info("WebSocket closed (code=$code).")
                if (!attempt.handshake.complete(HandshakeResult.Failed(statusCode = null))) {
                    attempt.termination.complete(Unit)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val statusCode = response?.code()
                logger.warn(
                    "WebSocket request failed (httpStatus=${statusCode ?: "none"}, " +
                        "failureType=${t.javaClass.simpleName}).",
                )
                if (!attempt.handshake.complete(HandshakeResult.Failed(statusCode))) {
                    attempt.termination.complete(Unit)
                }
            }
        }

        val webSocket = try {
            webSocketFactory.newWebSocket(request, listener)
        } catch (cancelled: CancellationException) {
            clearAttempt(attempt)
            throw cancelled
        } catch (io: IOException) {
            logger.warn("WebSocket request could not start (failureType=${io.javaClass.simpleName}).")
            clearAttempt(attempt)
            return SocketResult.Failed(statusCode = null)
        } catch (failure: Exception) {
            logger.warn(
                "WebSocket request could not start (failureType=${failure.javaClass.simpleName}).",
            )
            clearAttempt(attempt)
            return SocketResult.Failed(statusCode = null)
        }
        attempt.webSocket = webSocket

        try {
            val handshake = try {
                withTimeout(HANDSHAKE_TIMEOUT_MILLIS) { attempt.handshake.await() }
            } catch (_: TimeoutCancellationException) {
                logger.warn("WebSocket handshake timed out after $HANDSHAKE_TIMEOUT_MILLIS ms.")
                return SocketResult.Failed(statusCode = null)
            }
            when (handshake) {
                HandshakeResult.Opened -> {
                    if (!isCurrentUser(runGeneration, user)) {
                        return SocketResult.AuthenticationChanged
                    }
                    setState(runGeneration, GatewayState.Connected)
                    val connectedAtNanos = System.nanoTime()
                    val termination = coroutineScope {
                        val closeWhenOffline = launch {
                            networkMonitor.hasValidatedInternet.first { !it }
                            webSocket.cancel()
                        }
                        try {
                            attempt.termination.await()
                        } finally {
                            closeWhenOffline.cancel()
                        }
                    }
                    return SocketResult.Closed(
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectedAtNanos),
                    )
                }
                is HandshakeResult.Failed -> return SocketResult.Failed(handshake.statusCode)
            }
        } finally {
            webSocket.cancel()
            clearAttempt(attempt)
        }
    }

    private fun isCurrentUser(runGeneration: Long, user: AuthUser): Boolean {
        val generationMatches = synchronized(lock) { generation == runGeneration }
        return generationMatches && (authSession.currentState() as? AuthState.LoggedIn)?.user == user
    }

    private fun setState(runGeneration: Long, newState: GatewayState) {
        synchronized(lock) {
            if (generation == runGeneration && mutableState.value != newState) {
                logStateChange(mutableState.value, newState)
                mutableState.value = newState
            }
        }
    }

    private fun logStateChange(oldState: GatewayState, newState: GatewayState) {
        if (oldState == newState) return
        logger.info("Gateway state: ${oldState.diagnosticName()} -> ${newState.diagnosticName()}.")
    }

    private fun GatewayState.diagnosticName(): String = when (this) {
        GatewayState.Disconnected -> "disconnected"
        GatewayState.Connecting -> "connecting"
        GatewayState.Connected -> "connected"
        is GatewayState.Reconnecting ->
            "reconnecting(attempt=$attempt, retryInMillis=$retryInMillis)"
        is GatewayState.Error -> "error"
    }

    private fun closeActiveAttempt(runGeneration: Long) {
        synchronized(lock) {
            if (generation != runGeneration) return
            activeAttempt?.webSocket?.cancel()
            activeAttempt = null
        }
    }

    private fun clearAttempt(attempt: SocketAttempt) {
        synchronized(lock) {
            if (activeAttempt === attempt) activeAttempt = null
        }
    }

    private class SocketAttempt {
        val handshake = CompletableDeferred<HandshakeResult>()
        val termination = CompletableDeferred<Unit>()

        @Volatile
        var webSocket: WebSocket? = null
    }

    private sealed interface HandshakeResult {
        data object Opened : HandshakeResult
        data class Failed(val statusCode: Int?) : HandshakeResult
    }

    private sealed interface SocketResult {
        data class Closed(val connectedForMillis: Long) : SocketResult
        data class Failed(val statusCode: Int?) : SocketResult
        data object AuthenticationChanged : SocketResult
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64
        const val STABLE_CONNECTION_MILLIS = 30_000L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HANDSHAKE_TIMEOUT_MILLIS = 30_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GatewayConnectionBindings {
    @Binds
    @Singleton
    abstract fun bindGatewayConnection(implementation: GatewayManager): GatewayConnection
}
