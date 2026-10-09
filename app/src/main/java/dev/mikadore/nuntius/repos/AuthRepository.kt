package dev.mikadore.nuntius.repos

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AuthUser(
    val apiKey: String,
    val serverUrl: String,
    val credentialId: String?,
) {
    override fun toString(): String = "AuthUser(apiKey=[redacted], serverUrl=$serverUrl)"
}

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class LoggedIn(val user: AuthUser) : AuthState
}

@Singleton
class AuthRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private val credentialCipher = KeystoreCredentialCipher()
    private val migrationMutex = Mutex()
    @Volatile
    private var legacyMigrationComplete = false
    @Volatile
    private var latestAuthState: AuthState = AuthState.Loading

    val authState: Flow<AuthState> = dataStore.data
        .onStart { migrateLegacyCredential() }
        .map { preferences ->
            val encryptedToken = preferences[ENCRYPTED_API_TOKEN]
            val apiKey = encryptedToken?.let { encrypted ->
                runCatching { credentialCipher.decrypt(encrypted) }.getOrNull()
            }
            val state = if (apiKey.isNullOrBlank()) {
                AuthState.LoggedOut
            } else {
                AuthState.LoggedIn(
                    AuthUser(
                        apiKey = apiKey,
                        serverUrl = preferences[SERVER_URL].orEmpty(),
                        credentialId = preferences[CREDENTIAL_ID],
                    ),
                )
            }
            latestAuthState = state
            state
        }
        .flowOn(Dispatchers.IO)

    /** A synchronous in-memory view for request interceptors; persisted state remains authoritative. */
    internal fun currentAuthState(): AuthState = latestAuthState

    suspend fun saveCredentials(serverUrl: String, apiKey: String, credentialId: String) {
        require(serverUrl.startsWith("https://")) { "The Nuntius server must use HTTPS" }
        require(apiKey.isNotBlank()) { "The server credential cannot be blank" }
        require(credentialId.isNotBlank()) { "The server credential ID cannot be blank" }
        val encryptedToken = credentialCipher.encrypt(apiKey)
        dataStore.edit { preferences ->
            preferences[ENCRYPTED_API_TOKEN] = encryptedToken
            preferences[SERVER_URL] = serverUrl
            preferences[CREDENTIAL_ID] = credentialId
            // Remove credentials saved by earlier builds before credentials were encrypted.
            preferences.remove(LEGACY_API_TOKEN)
        }
        latestAuthState = AuthState.LoggedIn(
            AuthUser(apiKey = apiKey, serverUrl = serverUrl, credentialId = credentialId),
        )
    }

    suspend fun logOut() {
        dataStore.edit { preferences ->
            preferences.remove(ENCRYPTED_API_TOKEN)
            preferences.remove(SERVER_URL)
            preferences.remove(CREDENTIAL_ID)
            preferences.remove(LEGACY_API_TOKEN)
        }
        latestAuthState = AuthState.LoggedOut
    }

    private suspend fun migrateLegacyCredential() {
        if (legacyMigrationComplete) return
        migrationMutex.withLock {
            if (legacyMigrationComplete) return

            val legacyToken = dataStore.data.first()[LEGACY_API_TOKEN]
            val encryptedToken = legacyToken
                ?.takeIf(String::isNotBlank)
                ?.let { runCatching { credentialCipher.encrypt(it) }.getOrNull() }

            dataStore.edit { preferences ->
                if (preferences[LEGACY_API_TOKEN] != null) {
                    if (preferences[ENCRYPTED_API_TOKEN] == null && encryptedToken != null) {
                        preferences[ENCRYPTED_API_TOKEN] = encryptedToken
                    }
                    // Never leave a credential in the old plaintext preference.
                    preferences.remove(LEGACY_API_TOKEN)
                }
            }
            legacyMigrationComplete = true
        }
    }

    private companion object {
        val ENCRYPTED_API_TOKEN = stringPreferencesKey("api_token_encrypted")
        val LEGACY_API_TOKEN = stringPreferencesKey("api_token")
        val SERVER_URL = stringPreferencesKey("server_url")
        val CREDENTIAL_ID = stringPreferencesKey("credential_id")
    }
}
