package dev.mikadore.nuntius.gateway

import dev.mikadore.nuntius.network.AuthSession
import dev.mikadore.nuntius.repos.AuthState
import dev.mikadore.nuntius.repos.AuthUser
import dev.nuntius.gateway.proto.GatewayMessage
import dev.nuntius.gateway.proto.Greeting
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayManagerTest {
    private var manager: GatewayManager? = null

    @After
    fun tearDown() {
        manager?.disconnect()
    }

    @Test
    fun repeatedConnectUsesOneSocketAndDisconnectPreventsRetries() = runBlocking {
        val factory = FakeWebSocketFactory()
        val auth = FakeAuthSession(loggedIn("first-key"))
        val gateway = newGateway(auth, factory)

        gateway.connect()
        gateway.connect()
        factory.awaitSocketCount(1)
        assertEquals(GatewayState.Connecting, gateway.state.value)

        gateway.disconnect()
        gateway.disconnect()
        delay(75)

        assertEquals(GatewayState.Disconnected, gateway.state.value)
        assertEquals(1, factory.sockets.size)
        assertTrue(factory.sockets.single().cancelled)
    }

    @Test
    fun authChangeCancelsOldSocketBeforeOpeningTheNewSessionSocket() = runBlocking {
        val factory = FakeWebSocketFactory()
        val auth = FakeAuthSession(loggedIn("first-key"))
        val logger = FakeGatewayLogger()
        val gateway = newGateway(auth, factory, logger)

        gateway.connect()
        factory.awaitSocketCount(1)
        val oldSocket = factory.sockets.single()

        auth.set(loggedIn("replacement-key"))
        factory.awaitSocketCount(2)

        assertTrue(oldSocket.cancelled)
        assertEquals(GatewayState.Connecting, gateway.state.value)
        assertTrue(logger.messages.none { "first-key" in it || "replacement-key" in it })
        assertTrue(logger.messages.none { "server.example" in it })
        gateway.disconnect()
    }

    @Test
    fun openSocketExposesConnectedStateAndParsedProtobufEventsThenReconnectsOnClose() = runBlocking {
        val factory = FakeWebSocketFactory()
        val auth = FakeAuthSession(loggedIn("first-key"))
        val logger = FakeGatewayLogger()
        val gateway = newGateway(auth, factory, logger)
        val receivedEvent = async { gateway.events.first() }

        gateway.connect()
        factory.awaitSocketCount(1)
        val socket = factory.sockets.single()
        socket.open()

        withTimeout(2_000) { gateway.state.first { it == GatewayState.Connected } }
        assertTrue(gateway.sendText("opaque request"))
        assertEquals("opaque request", socket.lastSentText)
        val responseMessage = GatewayMessage.newBuilder()
            .setId("response-id")
            .setTimestampMs(1234L)
            .setGreeting(Greeting.newBuilder().setMessage("server greeting"))
            .build()
        socket.listener.onMessage(socket, ByteString.of(*responseMessage.toByteArray()))
        assertEquals(
            GatewayEvent.Message(responseMessage),
            withTimeout(2_000) { receivedEvent.await() },
        )

        socket.listener.onClosed(socket, 1000, "")
        withTimeout(2_000) { gateway.state.first { it is GatewayState.Reconnecting } }
        assertTrue(
            logger.messages.none {
                "opaque request" in it || "server greeting" in it || "response-id" in it
            },
        )
        gateway.disconnect()
    }

    @Test
    fun authenticationFailureIsReportedWithoutRetryingWithRejectedCredentials() = runBlocking {
        val factory = FakeWebSocketFactory()
        val logger = FakeGatewayLogger()
        val gateway = newGateway(FakeAuthSession(loggedIn("rejected-key")), factory, logger)

        gateway.connect()
        factory.awaitSocketCount(1)
        factory.sockets.single().fail(401)

        val error = withTimeout(2_000) {
            gateway.state.first { it is GatewayState.Error }
        }
        assertTrue((error as GatewayState.Error).message.contains("rejected"))
        assertTrue(logger.messages.any { "HTTP 401" in it })
        assertTrue(logger.messages.none { "rejected-key" in it || "server.example" in it })
        delay(75)
        assertEquals(1, factory.sockets.size)
        gateway.disconnect()
    }

    private fun newGateway(
        auth: FakeAuthSession,
        factory: FakeWebSocketFactory,
        logger: FakeGatewayLogger = FakeGatewayLogger(),
    ): GatewayManager = GatewayManager(auth, factory, FakeNetworkMonitor(), logger).also {
        manager = it
    }

    private fun loggedIn(apiKey: String) = AuthState.LoggedIn(
        AuthUser(
            apiKey = apiKey,
            serverUrl = "https://server.example/nuntius/",
            credentialId = "device",
        ),
    )

    private class FakeAuthSession(initial: AuthState) : AuthSession {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<AuthState> = mutableState
        override fun currentState(): AuthState = mutableState.value
        fun set(value: AuthState) {
            mutableState.value = value
        }
    }

    private class FakeNetworkMonitor : GatewayNetworkMonitor {
        override val hasValidatedInternet = MutableStateFlow(true)
    }

    private class FakeGatewayLogger : GatewayLogger {
        val messages = CopyOnWriteArrayList<String>()
        override fun debug(message: String) {
            messages.add(message)
        }
        override fun info(message: String) {
            messages.add(message)
        }
        override fun warn(message: String) {
            messages.add(message)
        }
    }

    private class FakeWebSocketFactory : WebSocket.Factory {
        val sockets = CopyOnWriteArrayList<FakeWebSocket>()

        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket =
            FakeWebSocket(request, listener).also(sockets::add)

        suspend fun awaitSocketCount(count: Int) {
            withTimeout(2_000) {
                while (sockets.size < count) delay(5)
            }
        }
    }

    private class FakeWebSocket(
        private val request: Request,
        val listener: WebSocketListener,
    ) : WebSocket {
        @Volatile
        var cancelled = false
            private set
        var lastSentText: String? = null
            private set

        override fun request(): Request = request
        override fun queueSize(): Long = 0
        override fun send(text: String): Boolean {
            lastSentText = text
            return !cancelled
        }
        override fun send(bytes: ByteString): Boolean = !cancelled
        override fun close(code: Int, reason: String?): Boolean = true
        override fun cancel() {
            cancelled = true
        }

        fun open() {
            listener.onOpen(
                this,
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build(),
            )
        }

        fun fail(statusCode: Int) {
            listener.onFailure(
                this,
                IOException("Handshake rejected"),
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(statusCode)
                    .message("Handshake rejected")
                    .build(),
            )
        }
    }
}
