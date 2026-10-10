package dev.mikadore.nuntius.gateway

import dev.mikadore.nuntius.network.ServerBaseUrl
import okhttp3.HttpUrl

/**
 * Gateway route: wss://nuntius-dev.rudd-alkaid.ts.net/api/gateway. The hostname comes from the
 * authenticated server configuration; OkHttp accepts its HTTPS form and upgrades it to WSS while
 * retaining any configured server path prefix.
 */
internal object GatewayEndpoint {
    const val PATH = "api/gateway"

    fun fromServerBaseUrl(serverBaseUrl: String): HttpUrl =
        ServerBaseUrl.parse(serverBaseUrl)
            .newBuilder()
            .addPathSegments(PATH)
            .build()
}
