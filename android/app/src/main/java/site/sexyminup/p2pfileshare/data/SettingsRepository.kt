package site.sexyminup.p2pfileshare.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    val serverUrl: Flow<String> = context.settingsDataStore.data.map { preferences ->
        normalizeServerUrl(preferences[SERVER_URL])
    }

    suspend fun saveServerUrl(url: String) {
        context.settingsDataStore.edit { preferences ->
            preferences[SERVER_URL] = url
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://files.dcout.site"
        const val SECONDARY_SERVER_URL = "https://files.sexyminup.site"
        private const val LEGACY_DEFAULT_SERVER_URL = "https://files.sexyminup.site"
        private val SERVER_URL = stringPreferencesKey("server_url")

        private fun normalizeServerUrl(value: String?): String {
            val clean = value?.trim()?.trimEnd('/').orEmpty()
            return when (clean) {
                "", LEGACY_DEFAULT_SERVER_URL -> DEFAULT_SERVER_URL
                else -> clean
            }
        }
    }
}
