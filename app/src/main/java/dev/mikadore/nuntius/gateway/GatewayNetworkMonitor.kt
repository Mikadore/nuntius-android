package dev.mikadore.nuntius.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface GatewayNetworkMonitor {
    val hasValidatedInternet: StateFlow<Boolean>
}

@Singleton
class AndroidGatewayNetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) : GatewayNetworkMonitor {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mutableHasValidatedInternet = MutableStateFlow(hasValidatedInternetNow())
    override val hasValidatedInternet: StateFlow<Boolean> =
        mutableHasValidatedInternet.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = refresh()

        override fun onLost(network: Network) = refresh()
    }

    init {
        connectivityManager.registerDefaultNetworkCallback(callback)
        refresh()
    }

    private fun refresh() {
        mutableHasValidatedInternet.value = hasValidatedInternetNow()
    }

    private fun hasValidatedInternetNow(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class GatewayNetworkBindings {
    @Binds
    @Singleton
    abstract fun bindGatewayNetworkMonitor(
        implementation: AndroidGatewayNetworkMonitor,
    ): GatewayNetworkMonitor
}
