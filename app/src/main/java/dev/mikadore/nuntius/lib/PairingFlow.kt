package dev.mikadore.nuntius.lib

import dev.mikadore.nuntius.network.model.GetPairingSession200Response
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/** Coordinates the generated API calls for one scanned pairing session. */
class PairingFlow(
    private val request: PairingRequest,
) {
    private val service = AuthService(request)

    suspend fun probe() = service.probe()

    suspend fun prepareClaim(): PairingSessionInfo {
        val session = service.getPairingSession(request.sessionUuid)
        ensureSessionActive(session)
        if (session.status == GetPairingSession200Response.StatusEnum.COMPLETE) {
            throw PairingException("This pairing request has already been completed")
        }
        val deadline = deadlineFor(session.expiresAt)
        return PairingSessionInfo(
            confirmationCode = generatePairingConfirmationCode(),
            deviceName = session.deviceName,
            expiresAt = session.expiresAt ?: Date(deadline),
            status = session.status,
        )
    }

    suspend fun claimAndWaitForApproval(
        info: PairingSessionInfo,
        deviceName: String,
        onProgress: (String) -> Unit,
    ): PairingCredential {
        val deadline = deadlineFor(info.expiresAt)
        return try {
            withTimeout((deadline - System.currentTimeMillis()).milliseconds) {
                coroutineScope {
                    val claim = async {
                        service.claimPairingSession(
                            sessionId = request.sessionUuid,
                            token = request.token,
                            confirmationCode = info.confirmationCode,
                            deviceName = deviceName,
                        )
                    }

                    try {
                        while (!claim.isCompleted) {
                            delay(POLL_INTERVAL_MILLIS.milliseconds)
                            if (claim.isCompleted) break

                            val session = try {
                                service.getPairingSession(request.sessionUuid)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                onProgress("Waiting for approval on the device that displayed the QR code…")
                                continue
                            }

                            ensureSessionActive(session)
                            onProgress(session.status.toApprovalMessage())
                        }

                        val credential = claim.await()
                        require(credential.tokenType == dev.mikadore.nuntius.network.model.ClaimPairingSession201Response.TokenTypeEnum.BEARER) {
                            "The server returned an unsupported credential type"
                        }
                        val accessToken = credential.accessToken
                            ?.takeIf { it.isNotBlank() }
                            ?: throw PairingException("The server did not return an access credential")
                        val credentialId = credential.credentialId
                            ?: throw PairingException("The server did not return a credential ID")
                        PairingCredential(accessToken, credentialId)
                    } catch (failure: Throwable) {
                        claim.cancel()
                        throw failure
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw PairingException("This pairing request expired before it could be completed", timeout)
        }
    }

    suspend fun cancel() {
        service.cancelPairingClaim(request.sessionUuid)
    }

    private fun deadlineFor(expiresAt: Date?): Long {
        val expiration = expiresAt?.time
            ?: (System.currentTimeMillis() + MAX_PAIRING_DURATION_MILLIS)
        val deadline = minOf(expiration, System.currentTimeMillis() + MAX_PAIRING_DURATION_MILLIS)
        if (deadline <= System.currentTimeMillis()) {
            throw PairingException("This pairing request has expired")
        }
        return deadline
    }

    private fun ensureSessionActive(session: GetPairingSession200Response) {
        when (session.status) {
            GetPairingSession200Response.StatusEnum.CANCELLED,
            GetPairingSession200Response.StatusEnum.CLOSED,
            GetPairingSession200Response.StatusEnum.EXPIRED,
            -> throw PairingException("This pairing request has expired or was cancelled")

            else -> Unit
        }
    }

    private fun GetPairingSession200Response.StatusEnum.toApprovalMessage(): String = when (this) {
        GetPairingSession200Response.StatusEnum.WAITING -> "Waiting for the other device to approve this pairing…"
        GetPairingSession200Response.StatusEnum.APPROVAL_PENDING -> "Approve this device on the device that displayed the QR code…"
        GetPairingSession200Response.StatusEnum.APPROVED -> "Approved. Exchanging credentials securely…"
        GetPairingSession200Response.StatusEnum.COMPLETE -> "Pairing complete. Securing credentials…"
        GetPairingSession200Response.StatusEnum.CANCELLED,
        GetPairingSession200Response.StatusEnum.CLOSED,
        GetPairingSession200Response.StatusEnum.EXPIRED,
        -> "This pairing request has ended."
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 1_500L
        private const val MAX_PAIRING_DURATION_MILLIS = 5 * 60 * 1_000L
    }
}

private val pairingCodeRandom = SecureRandom()

internal fun generatePairingConfirmationCode(): String =
    pairingCodeRandom.nextInt(1_000_000).toString().padStart(6, '0')

data class PairingSessionInfo(
    val confirmationCode: String,
    val deviceName: String?,
    val expiresAt: Date?,
    val status: GetPairingSession200Response.StatusEnum,
)

data class PairingCredential(
    val accessToken: String,
    val credentialId: UUID,
) {
    override fun toString(): String =
        "PairingCredential(accessToken=[redacted], credentialId=$credentialId)"
}
