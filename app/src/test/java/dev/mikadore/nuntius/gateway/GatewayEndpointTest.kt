package dev.mikadore.nuntius.gateway

import dev.mikadore.nuntius.network.InvalidServerUrlException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GatewayEndpointTest {
    @Test
    fun configuredGatewayServerResolvesToThePublishedEndpoint() {
        val endpoint = GatewayEndpoint.fromServerBaseUrl(
            "https://nuntius-dev.rudd-alkaid.ts.net/",
        )

        // OkHttp's native WebSocket client upgrades this HTTPS request URL to the supplied WSS URL.
        assertEquals(
            "https://nuntius-dev.rudd-alkaid.ts.net/api/gateway",
            endpoint.toString(),
        )
    }

    @Test
    fun endpointKeepsConfiguredBasePathAndUsesHttpsForSecureUpgrade() {
        val endpoint = GatewayEndpoint.fromServerBaseUrl("https://server.example/nuntius/v1/")

        assertEquals("https://server.example/nuntius/v1/api/gateway", endpoint.toString())
    }

    @Test
    fun endpointRejectsInsecureServerUrl() {
        assertThrows(InvalidServerUrlException::class.java) {
            GatewayEndpoint.fromServerBaseUrl("http://server.example/nuntius/")
        }
    }
}
