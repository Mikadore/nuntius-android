package dev.mikadore.nuntius

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mikadore.nuntius.components.AlertCard
import dev.mikadore.nuntius.gateway.GatewayConnection
import dev.mikadore.nuntius.gateway.GatewayState
import dev.mikadore.nuntius.repos.AuthRepository
import dev.mikadore.nuntius.repos.AuthState
import dev.mikadore.nuntius.repos.ConnectionRepository
import dev.mikadore.nuntius.repos.HealthStatus
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
    connectionRepository: ConnectionRepository,
    gatewayConnection: GatewayConnection,
) : ViewModel() {
    val connectedServer: StateFlow<String> = authRepository.authState
        .map { authState ->
            (authState as? AuthState.LoggedIn)?.user?.serverUrl.orEmpty()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = "",
        )

    val health: StateFlow<HealthStatus> = connectionRepository.observeHealth()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 5_000,
                replayExpirationMillis = 0,
            ),
            initialValue = HealthStatus.Checking,
        )

    val gatewayState: StateFlow<GatewayState> = gatewayConnection.state
}
@Preview
@Composable
fun HomePage(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val connectedServer by viewModel.connectedServer.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val gatewayState by viewModel.gatewayState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (connectedServer.isNotBlank()) {
            Text(
                text = "Connected to $connectedServer",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        GatewayStatus(gatewayState)
        HealthStatusCard(status = health)
    }
}

@Composable
private fun GatewayStatus(state: GatewayState) {
    val statusText = when (state) {
        GatewayState.Disconnected -> "Disconnected"
        GatewayState.Connecting -> "Connecting"
        GatewayState.Connected -> "Connected"
        is GatewayState.Reconnecting -> "Reconnecting"
        is GatewayState.Error -> state.message
    }
    Text(
        text = "Gateway: $statusText",
        style = MaterialTheme.typography.bodyMedium,
        color = if (state == GatewayState.Connected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun HealthStatusCard(status: HealthStatus) {
    when (status) {
        HealthStatus.Checking -> Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp))
            Text("Checking server connection…")
        }

        is HealthStatus.Offline -> AlertCard(
            title = "Server Offline",
            description = status.reason ?: "The server could not be reached.",
            icon = Icons.Default.Warning,
            timestamp = status.checkedAt,
        )

        is HealthStatus.Online -> {
            val details = buildList {
                add("Latency: ${status.latencyMs} ms")
                status.remoteIp?.let { add("Remote IP: $it") }
            }.joinToString(separator = " · ")

            AlertCard(
                title = "Server Online",
                description = details,
                icon = Icons.Default.CheckCircle,
                timestamp = status.checkedAt,
            )
        }
    }
}
