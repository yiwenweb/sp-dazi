package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import java.io.File

/**
 * 视频流控制仓库
 *
 * 负责:
 * 1. 通过 SSH 开启 C3 端 stream_encoderd (WebrtcStreamEnabled 参数)
 * 2. 部署并启动 MJPEG 流服务器 (mjpeg_stream.py)
 * 3. Android 端通过 HTTP 轮询 /frame 获取 JPEG 帧, 原生渲染
 *
 * 优势: 完全绕过 WebRTC + WebView, 用原生 Bitmap 显示, 低延迟、高兼容。
 */
class VideoStreamRepository(
    private val context: Context,
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "VideoStreamRepository"
        const val REMOTE_SCRIPT = "/data/spapp/spyl/mjpeg_stream.py"
        const val LOG_DIR = "/data/spapp/spyl/log"
        const val MJPEG_PORT = 5002
        private const val OPENPILOT = "/data/openpilot"
    }

    /** 开启 C3 端摄像头流 + 部署 MJPEG 服务器 */
    suspend fun enableStream(camera: String = "road"): Result<Unit> {
        // 0. 确保目录存在
        sshManager.executeCommand("mkdir -p /data/spapp/spyl/log")
        
        // 1. 开启 stream_encoderd (产生 H264 帧)
        val valStr = "1"
        val enableCmd = "echo -n '$valStr' > /data/params/d/WebrtcStreamEnabled && " +
                "echo -n '$valStr' > /data/params/d/IsDriverViewEnabled"
        sshManager.executeCommand(enableCmd)

        // 2. 部署 MJPEG 脚本
        val deployed = isScriptDeployed().getOrDefault(false)
        if (!deployed) {
            deployScript().getOrElse { return Result.failure(it) }
        }

        // 3. 杀旧进程 + 启动新进程（使用 Python 虚拟环境）
        val cameraArg = if (camera == "wideRoad") "wideRoad" else "road"
        val startCmd = buildString {
            append("pkill -f mjpeg_stream 2>/dev/null; ")
            append("cd $OPENPILOT && ")
            append(". /usr/local/venv/bin/activate && ")  // 激活虚拟环境
            append("export PYTHONPATH=$OPENPILOT && ")
            append("nohup python3 $REMOTE_SCRIPT --camera $cameraArg --port $MJPEG_PORT --host 0.0.0.0 ")
            append("> $LOG_DIR/mjpeg_stream.log 2>&1 & ")
            append("sleep 1 && echo 'MJPEG server started'")
        }
        return sshManager.executeCommand(startCmd).map { }
    }

    /** 关闭 C3 端摄像头流 + MJPEG 服务器 */
    suspend fun disableStream(): Result<Unit> {
        val disableCmd = "echo -n '0' > /data/params/d/WebrtcStreamEnabled && " +
                "echo -n '0' > /data/params/d/IsDriverViewEnabled"
        sshManager.executeCommand(disableCmd)
        return sshManager.executeCommand("pkill -f mjpeg_stream 2>/dev/null; echo stopped").map { }
    }

    /** 部署 MJPEG 脚本到 C3 */
    private suspend fun deployScript(): Result<Unit> {
        return try {
            val content = context.assets.open("mjpeg_stream.py")
                .bufferedReader().use { it.readText() }
            sshManager.writeTextFile(REMOTE_SCRIPT, content).map { }
        } catch (e: Exception) {
            Log.e(TAG, "deployScript failed", e)
            Result.failure(e)
        }
    }

    /** 脚本是否已部署 */
    private suspend fun isScriptDeployed(): Result<Boolean> {
        return sshManager.executeCommand("[ -f $REMOTE_SCRIPT ] && echo 1 || echo 0")
            .map { it.trim() == "1" }
    }

    /** 检查 MJPEG 服务器是否在运行 */
    suspend fun isStreamRunning(): Result<Boolean> {
        return sshManager.executeCommand("pgrep -f mjpeg_stream | wc -l")
            .map { (it.trim().toIntOrNull() ?: 0) > 0 }
    }

    /** 构造 MJPEG frame URL */
    fun frameUrl(host: String): String = "http://$host:$MJPEG_PORT/frame"

    /** 构造健康检查 URL */
    fun healthUrl(host: String): String = "http://$host:$MJPEG_PORT/health"
}
