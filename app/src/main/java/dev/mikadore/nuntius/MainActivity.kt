package dev.mikadore.nuntius

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.mikadore.nuntius.repos.AuthRepository
import dev.mikadore.nuntius.repos.AuthState
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val authState = authRepository.authState.first()
            val destination = when (authState) {
                is AuthState.LoggedIn -> HomeActivity::class.java
                AuthState.Loading, AuthState.LoggedOut -> LoginActivity::class.java
            }
            startActivity(Intent(this@MainActivity, destination))
            finish()
        }
    }
}
