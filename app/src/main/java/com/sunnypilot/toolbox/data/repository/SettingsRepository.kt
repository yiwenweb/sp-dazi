package com.sunnypilot.toolbox.data.repository

import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.C3SettingMeta
import com.sunnypilot.toolbox.model.C3SettingResult
import kotlinx.serialization.json.Json

class SettingsRepository(
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "SettingsRepository"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun listSettings(): Result<List<C3SettingMeta>> {
        return sshManager.executeCommand(
            "cd /data/openpilot && python c3_scripts/settings_bridge.py list"
        ).mapCatching { output ->
            json.decodeFromString(output)
        }
    }

    suspend fun setSetting(key: String, value: String): Result<C3SettingResult> {
        return sshManager.executeCommand(
            "cd /data/openpilot && python c3_scripts/settings_bridge.py set $key $value"
        ).mapCatching { output ->
            json.decodeFromString(output)
        }
    }
}
