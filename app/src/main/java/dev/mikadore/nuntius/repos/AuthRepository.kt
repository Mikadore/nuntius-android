package dev.mikadore.nuntius.repos

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AuthUser(val apiKey: String)

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val user: AuthUser) : AuthState
}

@Singleton
class AuthRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val authState: Flow<AuthState> = dataStore.data.map { preferences ->
        val apiKey = preferences[API_TOKEN]
        if (apiKey.isNullOrBlank()) {
            AuthState.LoggedOut
        } else {
            AuthState.LoggedIn(AuthUser(apiKey))
        }
    }

    suspend fun saveApiKey(apiKey: String) {
        dataStore.edit { preferences ->
            preferences[API_TOKEN] = apiKey
        }
    }

    suspend fun logOut() {
        dataStore.edit { preferences ->
            preferences.remove(API_TOKEN)
        }
    }

    private companion object {
        val API_TOKEN = stringPreferencesKey("api_token")
    }
}
