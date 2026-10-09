package dev.mikadore.nuntius

import dev.mikadore.nuntius.repos.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppConfig(
    val serverUrl: String = ""
)

@Singleton
class AppConfigStore @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    val config: Flow<AppConfig> = settingsRepository.serverUrl.map { serverUrl ->
        AppConfig(serverUrl = serverUrl)
    }

    suspend fun save(config: AppConfig) {
        settingsRepository.saveServerUrl(config.serverUrl)
    }
}
