package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class HudData(
    val speed: Float = 0f,
    val gear: String = "P",
    val steeringAngle: Float = 0f,
    val brakeLights: Boolean = false,
    val leftBlinker: Boolean = false,
    val rightBlinker: Boolean = false,
    val enabled: Boolean = false,
    val alertText1: String = "",
    val alertText2: String = "",
    val alertStatus: String = "normal",
    val leadDistance: Float? = null,
    val laneLeft: Float? = null,
    val laneRight: Float? = null,
    val gps: GpsData? = null,
    val timestamp: Double = 0.0
)

@Serializable
data class GpsData(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val altitude: Double = 0.0
)

class HudDataRepository(
    private val context: Context,
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "HudDataRepository"
        private const val REMOTE_SCRIPT = "/data/hud_data_server.py"
        private const val HUD_PORT = 5003
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /** 启动C3端HUD数据服务器 */
    suspend fun startHudServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. 检查脚本是否已部署
            val deployed = isScriptDeployed().getOrDefault(false)
            if (!deployed) {
                deployScript().getOrThrow()
            }
            
            // 2. 启动服务器
            val startCmd = buildString {
                append("pkill -f hud_data_server 2>/dev/null; ")
                append("cd /data/openpilot && . /usr/local/venv/bin/activate && ")
                append("export PYTHONPATH=/data/openpilot && ")
                append("nohup python $REMOTE_SCRIPT --port $HUD_PORT ")
                append("> /tmp/hud_data_server.log 2>&1 & echo started")
            }
            
            sshManager.executeCommand(startCmd).map { }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HUD server", e)
            Result.failure(e)
        }
    }
    
    /** 停止C3端HUD数据服务器 */
    suspend fun stopHudServer(): Result<Unit> {
        return sshManager.executeCommand("pkill -f hud_data_server 2>/dev/null; echo stopped").map { }
    }
    
    /** 获取HUD数据 */
    suspend fun fetchHudData(host: String): Result<HudData> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$host:$HUD_PORT/hud"
            val request = Request.Builder().url(url).build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                
                val body = response.body?.string() ?: "{}"
                val data = json.decodeFromString<HudData>(body)
                Result.success(data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch HUD data", e)
            Result.failure(e)
        }
    }
    
    private suspend fun deployScript(): Result<Unit> {
        return try {
            val content = context.assets.open("hud_data_server.py")
                .bufferedReader().use { it.readText() }
            sshManager.writeTextFile(REMOTE_SCRIPT, content).map { }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun isScriptDeployed(): Result<Boolean> {
        return sshManager.executeCommand("[ -f $REMOTE_SCRIPT ] && echo 1 || echo 0")
            .map { it.trim() == "1" }
    }
}
