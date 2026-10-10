package dev.mikadore.nuntius.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayRetryPolicyTest {
    @Test
    fun delayGrowsExponentiallyAndCapsAtConfiguredMaximum() {
        assertEquals(500L, GatewayRetryPolicy.delayMillis(attempt = 1, jitter = 0.0))
        assertEquals(3_000L, GatewayRetryPolicy.delayMillis(attempt = 2, jitter = 1.0))
        assertEquals(
            GatewayRetryPolicy.MAX_RETRY_DELAY_MILLIS,
            GatewayRetryPolicy.delayMillis(attempt = 20, jitter = 1.0),
        )
    }
}
