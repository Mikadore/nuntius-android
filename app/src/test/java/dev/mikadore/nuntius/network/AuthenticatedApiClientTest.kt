package dev.mikadore.nuntius.network

import dev.mikadore.nuntius.gateway.GatewayLogger
import dev.mikadore.nuntius.network.api.WorkflowsApi
import dev.mikadore.nuntius.network.model.PostWorkflowHealthRequest
import dev.mikadore.nuntius.repos.AuthState
import dev.mikadore.nuntius.repos.AuthUser
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedApiClientTest {
    @Test
    fun generatedServiceUsesCurrentServerAndInjectsBearerToken() = runBlocking {
        val session = FakeAuthSession(
            AuthState.LoggedIn(
                AuthUser(
                    apiKey = "first-token",
                    serverUrl = "https://api.example.test/nuntius",
                    credentialId = "credential-1",
                ),
            ),
        )
        val client = authenticatedClient(session)
        val api = client.service<WorkflowsApi>()

        val generatedRequest = api.postWorkflowHealth(PostWorkflowHealthRequest()).request()
        val authenticatedRequest = AuthenticatedBearerInterceptor(session)
            .authenticatedRequest(generatedRequest)

        assertEquals("api.example.test", generatedRequest.url().host())
        assertEquals("/nuntius/api/workflows/health", generatedRequest.url().encodedPath())
        assertEquals("Bearer first-token", authenticatedRequest.header("Authorization"))
    }

    @Test
    fun providedAuthenticatedClientAddsBearerTokenToGeneratedApiRequest() = runBlocking {
        val session = FakeAuthSession(loggedIn("injected-token", "https://api.example.test/"))
        val authorizationHeader = AtomicReference<String?>()
        val okHttpClient = dev.mikadore.nuntius.di.NetworkModule
            .provideAuthenticatedOkHttpClient(session, FakeGatewayLogger())
            .newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                authorizationHeader.set(request.header("Authorization"))
                okhttp3.Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        ResponseBody.create(
                            MediaType.parse("application/json"),
                            """{"status":"ok","service":"nuntius"}""",
                        ),
                    )
                    .build()
            }
            .build()
        val client = AuthenticatedApiClientImpl(session, okHttpClient)

        val response = client.workflows
            .postWorkflowHealth(PostWorkflowHealthRequest())
            .execute()

        assertTrue(response.isSuccessful)
        assertEquals("Bearer injected-token", authorizationHeader.get())
    }

    @Test
    fun serviceRequestRequiresAuthentication() {
        val client = authenticatedClient(FakeAuthSession(AuthState.LoggedOut))

        assertThrows(AuthenticationRequiredException::class.java) {
            runBlocking { client.service<WorkflowsApi>() }
        }
    }

    @Test
    fun credentialChangesAreUsedByExistingServiceAndLogoutRejectsIt() {
        runBlocking {
            val session = FakeAuthSession(loggedIn("old-token", "https://api.example.test/"))
            val client = authenticatedClient(session)
            val request = client.service<WorkflowsApi>()
                .postWorkflowHealth(PostWorkflowHealthRequest())
                .request()
            val interceptor = AuthenticatedBearerInterceptor(session)

            assertEquals("Bearer old-token", interceptor.authenticatedRequest(request).header("Authorization"))

            session.set(loggedIn("new-token", "https://api.example.test/", "credential-2"))
            assertEquals("Bearer new-token", interceptor.authenticatedRequest(request).header("Authorization"))

            session.set(AuthState.LoggedOut)
            assertThrows(IOException::class.java) { interceptor.authenticatedRequest(request) }
        }
    }

    @Test
    fun oldServiceCannotSendCredentialsToChangedServerOrigin() = runBlocking {
        val session = FakeAuthSession(loggedIn("first-token", "https://one.example.test/"))
        val client = authenticatedClient(session)
        val oldRequest = client.service<WorkflowsApi>()
            .postWorkflowHealth(PostWorkflowHealthRequest())
            .request()

        session.set(loggedIn("second-token", "https://two.example.test/", "credential-2"))

        assertThrows(IOException::class.java) {
            AuthenticatedBearerInterceptor(session).authenticatedRequest(oldRequest)
        }
        val currentRequest = client.service<WorkflowsApi>()
            .postWorkflowHealth(PostWorkflowHealthRequest())
            .request()
        assertEquals("two.example.test", currentRequest.url().host())
        assertEquals(
            "Bearer second-token",
            AuthenticatedBearerInterceptor(session).authenticatedRequest(currentRequest)
                .header("Authorization"),
        )
    }

    @Test
    fun rejectsInsecureOrAmbiguousServerUrls() {
        listOf(
            "http://api.example.test/",
            "https://user:password@api.example.test/",
            "https://api.example.test/path?query=value",
            "https://api.example.test/path#fragment",
            "https://api.example.test/../other/",
            "https://api.example.test/%2e%2e/other/",
        ).forEach { invalidUrl ->
            assertThrows(InvalidServerUrlException::class.java) {
                ServerBaseUrl.parse(invalidUrl)
            }
        }
    }

    @Test
    fun authenticatedClientDoesNotFollowRedirects() {
        val session = FakeAuthSession(loggedIn("token", "https://api.example.test/"))
        val client = dev.mikadore.nuntius.di.NetworkModule
            .provideAuthenticatedOkHttpClient(session, FakeGatewayLogger())

        assertFalse(client.followRedirects())
        assertFalse(client.followSslRedirects())
    }

    @Test
    fun interceptorRefusesRequestsToOtherOrigins() {
        val session = FakeAuthSession(loggedIn("token", "https://api.example.test/"))
        val interceptor = AuthenticatedBearerInterceptor(session)
        val foreignRequest = Request.Builder().url("https://other.example.test/api/workflows/health").build()

        assertThrows(IOException::class.java) { interceptor.authenticatedRequest(foreignRequest) }
    }

    @Test
    fun gatewayDiagnosticsConfirmHeaderAttachmentWithoutLoggingTokenOrServerUrl() {
        val session = FakeAuthSession(loggedIn("sensitive-token", "https://api.example.test/"))
        val logger = FakeGatewayLogger()
        val request = Request.Builder()
            .url("https://api.example.test/api/gateway")
            .header("Upgrade", "websocket")
            .build()

        AuthenticatedBearerInterceptor(session, logger).authenticatedRequest(request)

        assertTrue(logger.messages.any { "Attached bearer authorization" in it })
        assertTrue(logger.messages.none { "sensitive-token" in it || "api.example.test" in it })
    }

    private fun authenticatedClient(session: FakeAuthSession): AuthenticatedApiClient =
        AuthenticatedApiClientImpl(
            authSession = session,
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(AuthenticatedBearerInterceptor(session))
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )

    private fun loggedIn(
        token: String,
        serverUrl: String,
        credentialId: String = "credential-1",
    ) = AuthState.LoggedIn(
        AuthUser(apiKey = token, serverUrl = serverUrl, credentialId = credentialId),
    )

    private class FakeAuthSession(initialState: AuthState) : AuthSession {
        private val mutableState = MutableStateFlow(initialState)
        override val state: StateFlow<AuthState> = mutableState

        override fun currentState(): AuthState = mutableState.value

        fun set(authState: AuthState) {
            mutableState.value = authState
        }
    }

    private class FakeGatewayLogger : GatewayLogger {
        val messages = mutableListOf<String>()
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
}
