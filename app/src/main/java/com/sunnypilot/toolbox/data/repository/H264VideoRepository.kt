package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import kotlinx.coroutines.*
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * H264 视频流仓库 — WebSocket 接收 H264 帧，客户端硬件解码
 *
 * 原理：
 *   C3 端 h264_forward.py 转发 stream_encoderd 的 H264 帧
 *   Android 端通过 WebSocket 接收 H264 数据
 *   上层用 MediaCodec 硬件解码显示
 *
 * 优势：
 *   - C3 端零 CPU 占用（只转发，不解码）
 *   - Android 端硬件解码，流畅清晰
 *   - 可以显示原分辨率（1280x720）
 */
class H264VideoRepository(
    private val context: Context,
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "H264VideoRepo"
        const val WEBSOCKET_PORT = 5004
        const val REMOTE_SCRIPT = "/data/spapp/spyl/h264_forward.py"
        const val LOG_DIR = "/data/spapp/spyl/log"
        private const val OPENPILOT = "/data/openpilot"
        
        // 摄像头类型常量
        const val CAMERA_ROAD = "road"
        const val CAMERA_WIDE = "wideRoad"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private val currentCamera = AtomicReference(CAMERA_ROAD)
    
    // 帧回调（上层接收 H264 数据后自己解码）
    private var frameCallback: ((ByteArray) -> Unit)? = null
    private var errorCallback: ((String) -> Unit)? = null

    /** 连接到 WebSocket */
    fun connect(
        host: String,
        camera: String = CAMERA_ROAD,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        // 如果已连接且摄像头相同，不需要重连
        if (currentCamera.get() == camera && ws?.serverResponse?.code() == 101) {
            return
        }

        // 关闭旧连接
        ws?.close(1000, "switch camera or reconnect")

        currentCamera.set(camera)
        frameCallback = onFrame
        errorCallback = onError

        val url = "ws://$host:$WEBSOCKET_PORT/ws?camera=$camera"
        Log.d(TAG, "Connecting to WebSocket: $url")

        ws = client.newWebSocket(null, object : WebSocketListener() {
            private var pendingSize = 0
            private val frameBuffer = ByteArrayOutputStream()

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = org.json.JSONObject(text)
                    val type = json.getString("type")
                    
                    when (type) {
                        "frame" -> {
                            // 收到帧头，准备接收 H264 数据
                            pendingSize = json.getInt("size")
                            frameBuffer.reset()
                            Log.v(TAG, "Frame header received: size=$pendingSize bytes")
                        }
                        "heartbeat" -> {
                            // 心跳，忽略
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse WebSocket message: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    if (pendingSize > 0) {
                        // 累积 H264 数据
                        val data = bytes.toByteArray()
                        frameBuffer.write(data)
                        
                        // 如果数据完整，回调
                        if (frameBuffer.size() >= pendingSize) {
                            val h264Data = frameBuffer.toByteArray()
                            pendingSize = 0
                            frameCallback?.invoke(h264Data)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to process H264 data: ${e.message}")
                    pendingSize = 0
                    frameBuffer.reset()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
                pendingSize = 0
                frameBuffer.reset()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                pendingSize = 0
                frameBuffer.reset()
                errorCallback?.invoke(t.message ?: "WebSocket 连接失败")
            }
        })
    }

    /** 断开连接 */
    fun disconnect() {
        ws?.close(1000, "disconnect")
        ws = null
        currentCamera.set(CAMERA_ROAD)
        frameCallback = null
        errorCallback = null
    }

    /** 切换摄像头 */
    fun switchCamera(
        host: String,
        camera: String,
        onFrame: (ByteArray) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        connect(host, camera, onFrame, onError)
    }

    /** 检查服务是否在运行 */
    suspend fun isServiceRunning(): Result<Boolean> {
        return sshManager.executeCommand(
            "netstat -tuln 2>/dev/null | grep -q :$WEBSOCKET_PORT && echo 1 || echo 0"
        ).map { it.trim() == "1" }
    }

    /** 启动 H264 转发服务（自动部署脚本） */
    suspend fun startService(): Result<Unit> {
        // 确保目录存在
        sshManager.executeCommand("mkdir -p $LOG_DIR")

        // 检查脚本是否已部署，未部署则自动部署
        val deployed = isScriptDeployed().getOrDefault(false)
        if (!deployed) {
            Log.d(TAG, "Script not deployed, deploying now...")
            deployScript().fold(
                onSuccess = { Log.d(TAG, "Script deployed successfully") },
                onFailure = { e ->
                    Log.e(TAG, "Failed to deploy script", e)
                    return Result.failure(e)
                }
            )
        }

        // 检查是否已运行
        val running = isServiceRunning().getOrDefault(false)
        if (running) {
            Log.d(TAG, "H264 forward service already running")
            return Result.success(Unit)
        }

        // 杀掉旧进程（如果有的话）
        sshManager.executeCommand("pkill -f h264_forward.py 2>/dev/null")
        delay(1000)

        // 启动服务
        val startCmd = """
            cd $OPENPILOT && \\
            . /usr/local/venv/bin/activate && \\
            export PYTHONPATH=$OPENPILOT && \\
            nohup python3 $REMOTE_SCRIPT --port $WEBSOCKET_PORT --host 0.0.0.0 \\
            > $LOG_DIR/h264_forward.log 2>&1 & \\
            sleep 2 && \\
            netstat -tuln 2>/dev/null | grep -q :$WEBSOCKET_PORT && echo 'started' || echo 'failed'
        """.trimIndent()

        val result = sshManager.executeCommand(startCmd)
        
        return if (result.isSuccess) {
            val output = result.getOrNull()
            if (output.contains("started")) {
                Log.d(TAG, "H264 forward service started successfully")
                Result.success(Unit)
            } else {
                Log.e(TAG, "H264 forward service start failed: $output")
                Result.failure(Exception("H264 转发服务启动失败。请查看日志: $LOG_DIR/h264_forward.log"))
            }
        } else {
            Log.e(TAG, "Failed to execute start command", result.exceptionOrNull())
            Result.failure(result.exceptionOrNull() ?: Exception("启动命令执行失败"))
        }
    }

    /** 停止服务 */
    suspend fun stopService(): Result<Unit> {
        return sshManager.executeCommand("pkill -f h264_forward.py 2>/dev/null; echo stopped").map { }
    }

    /** 重启服务 */
    suspend fun restartService(): Result<Unit> {
        stopService()
        delay(1000)
        return startService()
    }

    /** 部署 H264 转发脚本到 C3 */
    suspend fun deployScript(): Result<Unit> {
        return try {
            val content = context.assets.open("h264_forward.py")
                .bufferedReader().use { it.readText() }
            
            sshManager.executeCommand("mkdir -p $LOG_DIR")
            sshManager.writeTextFile(REMOTE_SCRIPT, content).map {
                Log.d(TAG, "H264 forward script deployed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy H264 forward script", e)
            Result.failure(e)
        }
    }

    /** 检查脚本是否已部署 */
    suspend fun isScriptDeployed(): Result<Boolean> {
        return sshManager.executeCommand("[ -f $REMOTE_SCRIPT ] && echo 1 || echo 0")
            .map { it.trim() == "1" }
    }

    /** 获取服务状态信息 */
    suspend fun getServiceStatus(): Result<String> {
        val scriptCheck = isScriptDeployed().getOrDefault(false)
        val running = isServiceRunning().getOrDefault(false)
        
        val status = StringBuilder().apply {
            appendLine("脚本部署: ${if (scriptCheck) "✓ 已部署" else "✗ 未部署"}")
            appendLine("服务状态: ${if (running) "✓ 运行中" else "✗ 未运行"}")
            appendLine("端口: $WEBSOCKET_PORT")
            appendLine("脚本路径: $REMOTE_SCRIPT")
            appendLine("日志路径: $LOG_DIR/h264_forward.log")
        }.toString()
        
        return Result.success(status)
    }
}
