package dev.mikadore.nuntius.gateway

import kotlin.math.pow

/** Bounded exponential retry delay with caller-supplied jitter in [0, 1]. */
internal object GatewayRetryPolicy {
    const val MAX_RETRY_DELAY_MILLIS = 60_000L
    private const val BASE_DELAY_MILLIS = 1_000L

    fun delayMillis(attempt: Int, jitter: Double): Long {
        require(attempt >= 1)
        require(jitter in 0.0..1.0)
        val exponentialDelay = (BASE_DELAY_MILLIS * 2.0.pow((attempt - 1).coerceAtMost(20)))
            .toLong()
            .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
        val jitteredDelay = exponentialDelay * (0.5 + jitter)
        return jitteredDelay.toLong().coerceIn(500L, MAX_RETRY_DELAY_MILLIS)
    }
}
