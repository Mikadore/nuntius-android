package dev.mikadore.nuntius

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.mikadore.nuntius.repos.AuthRepository
import dev.mikadore.nuntius.repos.AuthState
import dev.mikadore.nuntius.ui.theme.NuntiusTheme
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class HomeActivity : ComponentActivity() {
    @Inject
    lateinit var authRepository: AuthRepository

    private var qrContents by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            authRepository.authState.collect { authState ->
                qrContents = (authState as? AuthState.LoggedIn)?.user?.apiKey.orEmpty()
            }
        }

        setContent {
            NuntiusTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        Text(text = qrContents)
                    }
                }
            }
        }
    }
}
