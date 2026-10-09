package dev.mikadore.nuntius.lib

import dev.mikadore.nuntius.network.api.DiagnosticsApi
import dev.mikadore.nuntius.network.api.PairingApi
import dev.mikadore.nuntius.network.model.ClaimPairingSessionRequest
import dev.mikadore.nuntius.network.model.DecidePairingClaimRequest
import dev.mikadore.nuntius.network.model.GetHealth200Response
import dev.mikadore.nuntius.network.model.GetPairingSession200Response
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Interceptor
import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

private const val PAIRING_URI_SCHEME = "nuntius"
private const val PAIRING_URI_HOST = "pair"
private const val CLAIM_PATH_SUFFIX = "/api/pair/claim"
private const val SESSION_COOKIE = "nuntius_session"
private val SESSION_ID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val PAIRING_TOKEN_PATTERN = Regex("^[A-Za-z0-9_-]{43}$")

data class PairingRequest(
    val serverHostname: String,
    val serverClaimPath: String,
    val serverBaseUrl: String,
    val sessionId: String,
    val token: String,
) {
    val sessionUuid: UUID
        get() = UUID.fromString(sessionId)
    val serverDisplayAddress: String
        get() = URI(serverBaseUrl).rawAuthority ?: serverHostname

    override fun toString(): String =
        "PairingRequest(serverHostname=$serverHostname, serverClaimPath=$serverClaimPath, " +
            "serverBaseUrl=$serverBaseUrl, sessionId=$sessionId, token=[redacted])"

    companion object {
        fun parse(raw: String): PairingRequest {
            require(raw.isNotBlank()) { "Pairing URI cannot be blank" }

            val uri = try {
                URI(raw)
            } catch (_: Exception) {
                throw IllegalArgumentException("Invalid pairing URI")
            }
            require(uri.scheme == PAIRING_URI_SCHEME && uri.host == PAIRING_URI_HOST) {
                "Invalid pairing URI"
            }
            require(
                uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null &&
                    uri.rawPath.isNullOrEmpty(),
            ) {
                "Invalid pairing URI"
            }

            val parameters = parseQuery(uri.rawQuery)
            require(parameters.keys == setOf("server", "session", "token")) {
                "Pairing URI is missing required information"
            }

            val server = parseServer(parameters.getValue("server"))
            val sessionId = parameters.getValue("session")
            require(SESSION_ID_PATTERN.matches(sessionId)) {
                "Pairing session ID is invalid"
            }
            val token = parameters.getValue("token")
            require(PAIRING_TOKEN_PATTERN.matches(token)) {
                "Pairing token is invalid"
            }

            val pathPrefix = server.rawPath.removeSuffix("api/pair/claim")
                .ifEmpty { "/" }
                .let { if (it.endsWith('/')) it else "$it/" }
            val serverBaseUrl = "https://${server.rawAuthority}$pathPrefix"

            return PairingRequest(
                serverHostname = server.host,
                serverClaimPath = server.rawPath,
                serverBaseUrl = serverBaseUrl,
                sessionId = sessionId,
                token = token,
            )
        }

        private fun parseQuery(rawQuery: String?): Map<String, String> {
            require(!rawQuery.isNullOrBlank()) { "Pairing URI is missing query parameters" }
            val parameters = linkedMapOf<String, String>()
            rawQuery.split('&').forEach { component ->
                val separator = component.indexOf('=')
                require(separator > 0) { "Invalid pairing URI query parameter" }
                val name = decodeQueryPart(component.substring(0, separator))
                val value = decodeQueryPart(component.substring(separator + 1))
                require(parameters.put(name, value) == null) {
                    "Pairing URI contains duplicate query parameters"
                }
            }
            return parameters
        }

        private fun decodeQueryPart(value: String): String = try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid pairing URI query parameter")
        }

        private fun parseServer(value: String): URI {
            val server = try {
                URI(value)
            } catch (_: Exception) {
                throw IllegalArgumentException("Pairing server address is invalid")
            }
            require(server.scheme == "https") {
                "Pairing server must use HTTPS"
            }
            require(server.host?.isNotBlank() == true && server.rawUserInfo == null) {
                "Pairing server address is invalid"
            }
            require(server.port == -1 || server.port in 1..65535) {
                "Pairing server port is invalid"
            }
            require(server.rawQuery == null && server.rawFragment == null) {
                "Pairing server address is invalid"
            }

            val path = server.rawPath
            require(path.endsWith(CLAIM_PATH_SUFFIX)) {
                "Pairing server address does not point to the Nuntius pairing endpoint"
            }
            require(path.split('/').none { it == "." || it == ".." } && '\\' !in path) {
                "Pairing server path is invalid"
            }
            require(!ENCODED_PATH_TRAVERSAL.containsMatchIn(path)) {
                "Pairing server path is invalid"
            }
            require(server.rawAuthority.none { it.isWhitespace() || it.isISOControl() }) {
                "Pairing server address is invalid"
            }
            return server
        }

        private val ENCODED_PATH_TRAVERSAL = Regex("%(2e|2f|5c)", RegexOption.IGNORE_CASE)
    }
}

/** Creates the generated Retrofit clients for one scanned, validated pairing URI. */
class AuthService(
    pairingRequest: PairingRequest,
) {
    private val retrofit = Retrofit.Builder()
        .baseUrl(pairingRequest.serverBaseUrl)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(Interceptor { chain ->
                    val original = chain.request()
                    if (original.url().encodedPath().contains("/api/pair/session/")) {
                        chain.proceed(
                            original.newBuilder()
                                .header("Cookie", "$SESSION_COOKIE=${pairingRequest.token}")
                                .build(),
                        )
                    } else {
                        chain.proceed(original)
                    }
                })
                .build(),
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val pairingApi: PairingApi = retrofit.create(PairingApi::class.java)
    val diagnosticsApi: DiagnosticsApi = retrofit.create(DiagnosticsApi::class.java)

    suspend fun probe() {
        val health = diagnosticsApi.getHealth().awaitBody()
        if (health.status != GetHealth200Response.StatusEnum.OK ||
            health.service != GetHealth200Response.ServiceEnum.NUNTIUS
        ) {
            throw PairingException("The server did not identify itself as a Nuntius server")
        }
    }

    suspend fun getPairingSession(sessionId: UUID): GetPairingSession200Response =
        pairingApi.getPairingSession(sessionId).awaitBody()

    suspend fun claimPairingSession(
        sessionId: UUID,
        token: String,
        confirmationCode: String,
        deviceName: String,
    ) = pairingApi.claimPairingSession(
        ClaimPairingSessionRequest()
            .session(sessionId)
            .token(token)
            .confirmationCode(confirmationCode)
            .deviceName(deviceName.take(80)),
    ).awaitBody()

    suspend fun cancelPairingClaim(sessionId: UUID) {
        pairingApi.decidePairingClaim(
            sessionId,
            DecidePairingClaimRequest().decision(DecidePairingClaimRequest.DecisionEnum.CANCEL),
        ).awaitSuccess()
    }
}

class PairingException(message: String, cause: Throwable? = null) : IOException(message, cause)

private suspend fun <T : Any> Call<T>.awaitBody(): T {
    val response = awaitResponse()
    response.requireSuccessful()
    return response.body() ?: throw PairingException("The server returned an empty response")
}

private suspend fun Call<Void>.awaitSuccess() {
    awaitResponse().requireSuccessful()
}

private fun Response<*>.requireSuccessful() {
    if (!isSuccessful) {
        errorBody()?.close()
        throw PairingException("The server rejected the pairing request (HTTP ${code()})")
    }
}

private suspend fun <T> Call<T>.awaitResponse(): Response<T> = suspendCancellableCoroutine { continuation ->
    enqueue(object : retrofit2.Callback<T> {
        override fun onResponse(call: Call<T>, response: Response<T>) {
            if (continuation.isActive) {
                continuation.resume(response)
            } else {
                response.errorBody()?.close()
            }
        }

        override fun onFailure(call: Call<T>, throwable: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(throwable)
            }
        }
    })
    continuation.invokeOnCancellation { cancel() }
}
