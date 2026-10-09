package dev.mikadore.nuntius.repos

import android.util.Log
import dev.mikadore.nuntius.network.AuthenticatedApiClient
import dev.mikadore.nuntius.network.model.PostWorkflowHealthRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import retrofit2.awaitResponse
import kotlin.time.Duration.Companion.milliseconds

sealed interface HealthStatus {
    data object Checking : HealthStatus

    data class Online(
        val latencyMs: Long,
        val remoteIp: String?,
        val checkedAt: String = "",
    ) : HealthStatus

    data class Offline(
        val reason: String?,
        val checkedAt: String = "",
    ) : HealthStatus
}

@Singleton
class ConnectionRepository @Inject constructor(
    private val apiClient: AuthenticatedApiClient,
) {
    suspend fun healthCheck(): HealthStatus {
        return try {
            val response =
                apiClient.workflows.postWorkflowHealth(PostWorkflowHealthRequest()).awaitResponse()

            if (!response.isSuccessful) {
                return HealthStatus.Offline(
                    "HTTP ${response.code()}"
                )
            }

            val raw = response.raw()
            val latencyMs =
                raw.receivedResponseAtMillis() -
                        raw.sentRequestAtMillis()

            HealthStatus.Online(
                latencyMs = latencyMs,
                remoteIp = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            HealthStatus.Offline(e.message)
        }
    }

    fun observeHealth(): Flow<HealthStatus> = flow {
        emit(HealthStatus.Checking)

        while (true) {
            val result = withTimeoutOrNull(5_000.milliseconds) {
                healthCheck()
            } ?: HealthStatus.Offline("Request timed out")
            val timestampedResult = result.withCurrentTime()
            Log.d("ConnectionRepository", "collected health check: $timestampedResult")

            emit(timestampedResult)

            delay(15_000.milliseconds)
        }
    }

    private fun HealthStatus.withCurrentTime(): HealthStatus {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        return when (this) {
            HealthStatus.Checking -> this
            is HealthStatus.Offline -> copy(checkedAt = time)
            is HealthStatus.Online -> copy(checkedAt = time)
        }
    }
}
