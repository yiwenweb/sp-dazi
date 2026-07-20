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
            // 关键: 用 setsid 完全脱离会话 + 重定向 stdin(</dev/null),
            // 否则后台 python 进程会继承 SSH exec channel 的文件描述符,
            // 导致 channel 一直不关闭, executeCommand 永远阻塞(界面卡在"启动中")。
            val startCmd = buildString {
                append("cd /data/openpilot && ")
                append(". /usr/local/venv/bin/activate && ")
                append("export PYTHONPATH=/data/openpilot && ")
                append("setsid nohup python3 $REMOTE_SCRIPT --port $HUD_PORT --host 0.0.0.0 ")
                append("</dev/null > $LOG_DIR/hud_data_server.log 2>&1 & ")
                append("echo launched")
            }

            val launchResult = sshManager.executeCommand(startCmd)
            if (launchResult.isFailure) {
                Log.e(TAG, "HUD start command failed", launchResult.exceptionOrNull())
                return@withContext Result.failure(launchResult.exceptionOrNull() ?: Exception("HUD 启动命令执行失败"))
            }

            // 在 Kotlin 端轮询进程, 确认服务真的起来了(最多等 ~6 秒)
            repeat(6) { attempt ->
                kotlinx.coroutines.delay(1000)
                if (isHudRunning().getOrDefault(false)) {
                    Log.d(TAG, "HUD server started successfully (after ${attempt + 1}s)")
                    return@withContext Result.success(Unit)
                }
            }

            val log = sshManager.executeCommand("tail -n 20 $LOG_DIR/hud_data_server.log 2>/dev/null")
                .getOrDefault("(无法读取日志)")
            Log.e(TAG, "HUD server failed to start. Log:\n$log")
            Result.failure(Exception("HUD 服务启动失败。\n日志尾部:\n$log"))
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
