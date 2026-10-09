package dev.mikadore.nuntius

import dev.mikadore.nuntius.lib.PairingRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingRequestTest {
    @Test
    fun parsesPairingUriAndDerivesRetrofitBaseUrl() {
        val request = PairingRequest.parse(pairingUri())

        assertEquals("nuntius.example", request.serverHostname)
        assertEquals("/api/pair/claim", request.serverClaimPath)
        assertEquals("https://nuntius.example/", request.serverBaseUrl)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", request.sessionUuid.toString())
    }

    @Test
    fun preservesAValidatedServerPathPrefix() {
        val request = PairingRequest.parse(
            pairingUri(server = "https://nuntius.example/gateway/api/pair/claim"),
        )

        assertEquals("https://nuntius.example/gateway/", request.serverBaseUrl)
    }

    @Test
    fun retainsTheServerPortForTheConfirmationPrompt() {
        val request = PairingRequest.parse(
            pairingUri(server = "https://nuntius.example:8443/api/pair/claim"),
        )

        assertEquals("nuntius.example:8443", request.serverDisplayAddress)
        assertEquals("https://nuntius.example:8443/", request.serverBaseUrl)
    }

    @Test
    fun rejectsInsecureOrUnexpectedServerEndpoints() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingRequest.parse(pairingUri(server = "http://nuntius.example/api/pair/claim"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRequest.parse(pairingUri(server = "https://nuntius.example/other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRequest.parse(pairingUri(server = "https://user@nuntius.example/api/pair/claim"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRequest.parse(pairingUri(server = "https://nuntius.example/%2e%2e/api/pair/claim"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRequest.parse("nuntius://pair:42?${pairingUri().substringAfter('?')}")
        }
    }

    @Test
    fun rejectsMalformedSessionTokensAndDuplicateParameters() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingRequest.parse(pairingUri(session = "not-a-uuid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRequest.parse(pairingUri(token = "too-short"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingRequest.parse(pairingUri() + "&token=${"A".repeat(43)}")
        }
    }

    @Test
    fun neverIncludesThePairingTokenInItsStringRepresentation() {
        val token = "A".repeat(43)
        val request = PairingRequest.parse(pairingUri(token = token))

        assertFalse(request.toString().contains(token))
        assertTrue(request.toString().contains("[redacted]"))
    }

    private fun pairingUri(
        server: String = "https://nuntius.example/api/pair/claim",
        session: String = "123e4567-e89b-12d3-a456-426614174000",
        token: String = "A".repeat(43),
    ): String = "nuntius://pair?server=${encode(server)}&session=$session&token=$token"

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
}
