package dev.mikadore.nuntius.gateway

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Connection state is safe to expose to UI and notifications; it never contains credentials or URLs. */
sealed interface GatewayState {
    data object Disconnected : GatewayState
    data object Connecting : GatewayState
    data object Connected : GatewayState

    data class Reconnecting(
        val attempt: Int,
        val retryInMillis: Long?,
        val reason: String,
    ) : GatewayState

    data class Error(val message: String) : GatewayState
}

/** Opaque transport events. The gateway does not interpret or persist application messages. */
sealed interface GatewayEvent {
    data class Text(val value: String) : GatewayEvent
    data class Binary(val value: ByteArray) : GatewayEvent
}

/** Application-wide control and observation surface for the authenticated gateway socket. */
interface GatewayConnection {
    val state: StateFlow<GatewayState>
    val events: Flow<GatewayEvent>

    /** Starts monitoring the current authenticated session. Repeated calls are idempotent. */
    fun connect()

    /** Cancels session monitoring and closes the active socket. Repeated calls are idempotent. */
    fun disconnect()

    /** Sends an opaque text frame; returns false if no open socket accepts it. */
    fun sendText(message: String): Boolean

    /** Sends an opaque binary frame; returns false if no open socket accepts it. */
    fun sendBinary(message: ByteArray): Boolean
}
