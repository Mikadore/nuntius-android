package dev.mikadore.nuntius

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mikadore.nuntius.repos.AuthRepository
import dev.mikadore.nuntius.repos.AuthState
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    authRepository: AuthRepository,
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
}

@Composable
fun HomePage(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val connectedServer by viewModel.connectedServer.collectAsStateWithLifecycle()

    Text(
        text = "Connected to $connectedServer",
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
