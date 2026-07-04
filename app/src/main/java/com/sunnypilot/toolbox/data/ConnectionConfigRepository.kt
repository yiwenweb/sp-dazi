package com.sunnypilot.toolbox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sunnypilot.toolbox.model.AuthType
import com.sunnypilot.toolbox.model.ConnectionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "connection_config")

class ConnectionConfigRepository(context: Context) {
    private val dataStore = context.dataStore

    companion object {
        private val HOST_KEY = stringPreferencesKey("host")
        private val PORT_KEY = intPreferencesKey("port")
        private val USERNAME_KEY = stringPreferencesKey("username")
        private val AUTH_TYPE_KEY = stringPreferencesKey("auth_type")
        private val PASSWORD_KEY = stringPreferencesKey("password")
        private val PRIVATE_KEY_KEY = stringPreferencesKey("private_key")
        private val KEY_FILE_NAME_KEY = stringPreferencesKey("key_file_name")
    }

    val configFlow: Flow<ConnectionConfig> = dataStore.data.map { prefs ->
        ConnectionConfig(
            host = prefs[HOST_KEY] ?: "",
            port = prefs[PORT_KEY] ?: 22,
            username = prefs[USERNAME_KEY] ?: SshManager.DEFAULT_USER,
            authType = AuthType.valueOf(prefs[AUTH_TYPE_KEY] ?: AuthType.PASSWORD.name),
            password = prefs[PASSWORD_KEY] ?: "",
            privateKeyContent = prefs[PRIVATE_KEY_KEY] ?: "",
            savedKeyFileName = prefs[KEY_FILE_NAME_KEY] ?: ""
        )
    }

    suspend fun save(config: ConnectionConfig) {
        dataStore.edit { prefs ->
            prefs[HOST_KEY] = config.host
            prefs[PORT_KEY] = config.port
            prefs[USERNAME_KEY] = config.username
            prefs[AUTH_TYPE_KEY] = config.authType.name
            prefs[PASSWORD_KEY] = config.password
            prefs[PRIVATE_KEY_KEY] = config.privateKeyContent
            prefs[KEY_FILE_NAME_KEY] = config.savedKeyFileName
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
