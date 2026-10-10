package dev.mikadore.nuntius.gateway

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diagnostics for the gateway and its authenticated HTTP upgrade request. Messages must not
 * include credentials, server URLs, exception messages, or WebSocket frame contents.
 */
interface GatewayLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
}

@Singleton
class AndroidGatewayLogger @Inject constructor() : GatewayLogger {
    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String) {
        Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "NuntiusGateway"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GatewayLoggerBindings {
    @Binds
    @Singleton
    abstract fun bindGatewayLogger(implementation: AndroidGatewayLogger): GatewayLogger
}
