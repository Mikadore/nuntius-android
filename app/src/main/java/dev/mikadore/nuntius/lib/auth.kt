package dev.mikadore.nuntius.lib

import android.net.Uri
import androidx.core.net.toUri

private const val PAIRING_URI_SCHEME = "nuntius"
private const val PAIRING_URI_HOST = "pair"

data class PairingRequest(
    val serverHostname: String,
    val serverClaimPath: String,
    val sessionId: String,
    val token: String,
) {
    companion object {
        fun parse(raw: String): PairingRequest {
            require(raw.isNotBlank()) { "Pairing URI cannot be blank" }

            val uri = raw.toUri()
            require(uri.scheme == PAIRING_URI_SCHEME && uri.host == PAIRING_URI_HOST) {
                "Invalid pairing URI"
            }

            val server = uri.getQueryParameter("server")
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
                ?: throw IllegalArgumentException("Missing or invalid `server` query parameter")

            val sessionId = uri.getQueryParameter("session")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing or empty `session` query parameter")

            val token = uri.getQueryParameter("token")
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Missing or empty `token` query parameter")

            require(server.scheme == "https") {
                "Pairing server URI must use HTTPS"
            }
            val serverHostname = server.host
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Pairing server URI is missing its hostname")
            val serverClaimPath = server.path
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Pairing server URI is missing its claim path")

            return PairingRequest(
                serverHostname,
                serverClaimPath,
                sessionId,
                token,
            )
        }
    }
}
