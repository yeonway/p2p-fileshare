package site.sexyminup.p2pfileshare.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    val serverUrl: Flow<String> = context.settingsDataStore.data.map {
        DEFAULT_SERVER_URL
    }

    suspend fun saveServerUrl(url: String = DEFAULT_SERVER_URL) {
        context.settingsDataStore.edit { preferences ->
            preferences[SERVER_URL] = DEFAULT_SERVER_URL
        }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://files.dcout.site"
        const val DEFAULT_SERVER_HOST = "files.dcout.site"
        private val SERVER_URL = stringPreferencesKey("server_url")
    }
}
