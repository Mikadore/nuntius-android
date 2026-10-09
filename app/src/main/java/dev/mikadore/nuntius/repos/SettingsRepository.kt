package dev.mikadore.nuntius.repos

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val serverUrl: Flow<String> = dataStore.data.map { preferences ->
        preferences[SERVER_URL].orEmpty()
    }

    suspend fun saveServerUrl(serverUrl: String) {
        dataStore.edit { preferences ->
            preferences[SERVER_URL] = serverUrl
        }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
    }
}
