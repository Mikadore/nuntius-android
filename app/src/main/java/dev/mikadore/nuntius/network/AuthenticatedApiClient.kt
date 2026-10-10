package dev.mikadore.nuntius.network

import dev.mikadore.nuntius.network.api.DiagnosticsApi
import dev.mikadore.nuntius.network.api.PairingApi
import dev.mikadore.nuntius.network.api.ScaffoldApi
import dev.mikadore.nuntius.network.api.WorkflowsApi
import dev.mikadore.nuntius.gateway.GatewayLogger
import dev.mikadore.nuntius.repos.AuthRepository
import dev.mikadore.nuntius.repos.AuthState
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Provides generated Retrofit APIs for the currently authenticated server. */
interface AuthenticatedApiClient {
    val diagnostics: DiagnosticsApi
    val pairing: PairingApi
    val scaffold: ScaffoldApi
    val workflows: WorkflowsApi

    suspend fun <T : Any> service(serviceType: Class<T>): T
}

/** Reified convenience for repositories that consume a generated API interface. */
suspend inline fun <reified T : Any> AuthenticatedApiClient.service(): T =
    service(T::class.java)

/** Raised when an authenticated API service is requested without a logged-in account. */
class AuthenticationRequiredException : IllegalStateException(
    "An authenticated Nuntius account is required to use this API",
)

/** Raised when the configured server URL is not a safe HTTPS base URL. */
class InvalidServerUrlException : IllegalArgumentException(
    "The configured Nuntius server URL is invalid",
)

/**
 * Keeps the latest auth state available to OkHttp without reading DataStore from an interceptor.
 * The repository remains the source of truth; this is only an in-memory request-time snapshot.
 */
interface AuthSession {
    val state: StateFlow<AuthState>
    fun currentState(): AuthState
}

@Singleton
class AuthSessionImpl @Inject constructor(
    private val authRepository: AuthRepository,
) : AuthSession {
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Loading)
    override val state: StateFlow<AuthState> = mutableState.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            authRepository.authState.collect { authState ->
                mutableState.value = authState
            }
        }
    }

    override fun currentState(): AuthState = authRepository.currentAuthState()
}

/** Adds the active account's bearer token only to its validated HTTPS origin. */
class AuthenticatedBearerInterceptor(
    private val authSession: AuthSession,
    private val gatewayLogger: GatewayLogger? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(authenticatedRequest(chain.request()))

    internal fun authenticatedRequest(request: Request): Request {
        val isGatewayUpgrade = request.header("Upgrade").equals("websocket", ignoreCase = true)
        val user = (authSession.currentState() as? AuthState.LoggedIn)?.user
            ?: refuse("No authenticated session is available.", isGatewayUpgrade)
        if (user.apiKey.isBlank()) {
            refuse("The active credential is empty.", isGatewayUpgrade)
        }

        val configuredServer = try {
            ServerBaseUrl.parse(user.serverUrl)
        } catch (_: InvalidServerUrlException) {
            refuse("The configured server URL is invalid.", isGatewayUpgrade)
        }
        if (!request.url().isHttps() || !request.url().sameOriginAs(configuredServer)) {
            refuse(
                "Refused to send authorization outside the configured HTTPS origin.",
                isGatewayUpgrade,
            )
        }

        if (isGatewayUpgrade) {
            gatewayLogger?.debug("Attached bearer authorization to the same-origin WebSocket upgrade.")
        }
        return request.newBuilder()
            .header("Authorization", "Bearer ${user.apiKey}")
            .build()
    }

    private fun refuse(reason: String, isGatewayUpgrade: Boolean): Nothing {
        if (isGatewayUpgrade) gatewayLogger?.warn("Gateway authorization refused: $reason")
        throw IOException(reason)
    }
}

@Singleton
class AuthenticatedApiClientImpl @Inject constructor(
    private val authSession: AuthSession,
    private val okHttpClient: OkHttpClient,
) : AuthenticatedApiClient {
    private val retrofits = ConcurrentHashMap<String, Retrofit>()
    private val services = ConcurrentHashMap<ServiceCacheKey, Any>()

    override val diagnostics: DiagnosticsApi
        get() = serviceForCurrentUser(DiagnosticsApi::class.java)

    override val pairing: PairingApi
        get() = serviceForCurrentUser(PairingApi::class.java)

    override val scaffold: ScaffoldApi
        get() = serviceForCurrentUser(ScaffoldApi::class.java)

    override val workflows: WorkflowsApi
        get() = serviceForCurrentUser(WorkflowsApi::class.java)

    override suspend fun <T : Any> service(serviceType: Class<T>): T {
        val currentState = authSession.currentState()
        val authState = if (currentState == AuthState.Loading) {
            authSession.state.first { it !is AuthState.Loading }
        } else {
            currentState
        }
        val user = (authState as? AuthState.LoggedIn)?.user
            ?: throw AuthenticationRequiredException()
        if (user.apiKey.isBlank()) throw AuthenticationRequiredException()

        return serviceFor(user.serverUrl, serviceType)
    }

    private fun <T : Any> serviceForCurrentUser(serviceType: Class<T>): T {
        val user = (authSession.currentState() as? AuthState.LoggedIn)?.user
            ?: throw AuthenticationRequiredException()
        if (user.apiKey.isBlank()) throw AuthenticationRequiredException()
        return serviceFor(user.serverUrl, serviceType)
    }

    private fun <T : Any> serviceFor(serverUrl: String, serviceType: Class<T>): T {
        val baseUrl = ServerBaseUrl.parse(serverUrl)
        val retrofit = retrofits.computeIfAbsent(baseUrl.toString()) {
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        @Suppress("UNCHECKED_CAST")
        return services.computeIfAbsent(ServiceCacheKey(baseUrl.toString(), serviceType)) {
            retrofit.create(serviceType)
        } as T
    }

    private data class ServiceCacheKey(
        val baseUrl: String,
        val serviceType: Class<*>,
    )
}

/** Validates and normalizes a configured server base URL, including any path prefix. */
internal object ServerBaseUrl {
    private val encodedPathTraversal = Regex("%(2e|2f|5c)", RegexOption.IGNORE_CASE)

    fun parse(raw: String): HttpUrl {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw InvalidServerUrlException()
        }
        val rawPath = uri.rawPath.orEmpty()
        if (
            raw.isBlank() || raw.any { it.isWhitespace() || it.isISOControl() } ||
            !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            (uri.port != -1 && uri.port !in 1..65535) || '\\' in rawPath ||
            rawPath.split('/').any { it == "." || it == ".." } ||
            encodedPathTraversal.containsMatchIn(rawPath)
        ) {
            throw InvalidServerUrlException()
        }

        val parsed = HttpUrl.parse(raw) ?: throw InvalidServerUrlException()
        if (parsed.scheme() != "https" || parsed.username().isNotEmpty() || parsed.password().isNotEmpty()) {
            throw InvalidServerUrlException()
        }
        val path = parsed.encodedPath().let { if (it.endsWith('/')) it else "$it/" }
        return parsed.newBuilder().encodedPath(path).build()
    }
}

private fun HttpUrl.sameOriginAs(other: HttpUrl): Boolean =
    scheme() == other.scheme() && host() == other.host() && port() == other.port()
