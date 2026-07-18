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
        private const val REMOTE_SCRIPT = "/data/spapp/spyl/hud_data_server.py"
        private const val LOG_DIR = "/data/spapp/spyl/log"
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
    
    /** 启动C3端HUD数据服务器（智能启动，不杀现有进程） */
    suspend fun startHudServer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 0. 确保目录存在
            sshManager.executeCommand("mkdir -p $LOG_DIR")
            
            // 1. 先检查服务是否已经在运行
            val running = isHudRunning().getOrDefault(false)
            if (running) {
                Log.d(TAG, "HUD server already running, skipping startup")
                return@withContext Result.success(Unit)
            }
            
            // 2. 检查脚本是否已部署
            val deployed = isScriptDeployed().getOrDefault(false)
            if (!deployed) {
                deployScript().getOrThrow()
            }
            
            // 3. 启动服务器（不杀旧进程）
            val startCmd = buildString {
                append("cd /data/openpilot && ")
                append(". /usr/local/venv/bin/activate && ")
                append("export PYTHONPATH=/data/openpilot && ")
                append("nohup python3 $REMOTE_SCRIPT --port $HUD_PORT --host 0.0.0.0 ")
                append("> $LOG_DIR/hud_data_server.log 2>&1 & ")
                
                // 等待并验证服务启动
                append("sleep 2; ")
                
                // 检查进程是否真的在运行
                append("if pgrep -f hud_data_server > /dev/null; then ")
                append("  echo 'HUD server started'; ")
                append("else ")
                append("  echo 'ERROR: HUD server failed to start'; ")
                append("  tail -n 20 $LOG_DIR/hud_data_server.log; ")
                append("  exit 1; ")
                append("fi")
            }
            
            val result = sshManager.executeCommand(startCmd)
            
            // 检查启动结果
            result.fold(
                onSuccess = { output ->
                    if (output.contains("ERROR")) {
                        Log.e(TAG, "HUD start failed: $output")
                        return@withContext Result.failure(Exception("HUD server failed to start. Check log: $LOG_DIR/hud_data_server.log"))
                    }
                    Log.d(TAG, "HUD start success: $output")
                },
                onFailure = { e ->
                    Log.e(TAG, "HUD start command failed", e)
                    return@withContext Result.failure(e)
                }
            )
            
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HUD server", e)
            Result.failure(e)
        }
    }
    
    /** 强制重启HUD服务 */
    suspend fun restartHudServer(): Result<Unit> {
        stopHudServer()
        kotlinx.coroutines.delay(1000)
        return startHudServer()
    }
    
    /** 检查HUD服务器是否在运行 */
    suspend fun isHudRunning(): Result<Boolean> {
        return sshManager.executeCommand("pgrep -f hud_data_server | wc -l")
            .map { (it.trim().toIntOrNull() ?: 0) > 0 }
    }
    
    /** 重新部署HUD脚本（强制覆盖） */
    suspend fun redeployScript(): Result<String> {
        return try {
            val content = context.assets.open("hud_data_server.py")
                .bufferedReader().use { it.readText() }
            
            // 写入文件
            sshManager.writeTextFile(REMOTE_SCRIPT, content).fold(
                onSuccess = {
                    Log.d(TAG, "HUD script redeployed successfully")
                    Result.success("✓ HUD脚本部署成功\n路径: $REMOTE_SCRIPT\n大小: ${content.length} bytes")
                },
                onFailure = { e ->
                    Log.e(TAG, "Failed to redeploy HUD script", e)
                    Result.failure(e)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read HUD script from assets", e)
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
