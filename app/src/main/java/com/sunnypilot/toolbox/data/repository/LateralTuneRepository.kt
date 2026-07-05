package com.sunnypilot.toolbox.data.repository

import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.LateralTuneResult
import java.io.File
import kotlinx.serialization.json.Json

class LateralTuneRepository(
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "LateralTuneRepository"
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(maxSegments: Int = 5): Result<LateralTuneResult> {
        return sshManager.executeCommand(
            "cd /data/openpilot && python3 c3_scripts/lateral_tune_export.py --max-segments $maxSegments"
        ).mapCatching { output ->
            json.decodeFromString(LateralTuneResult.serializer(), output)
        }
    }

    suspend fun downloadReport(remotePath: String, localFile: File): Result<Unit> {
        return sshManager.downloadFile(remotePath, localFile)
    }
}
