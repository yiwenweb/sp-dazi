package com.sunnypilot.toolbox.data.repository

import com.sunnypilot.toolbox.data.SshManager

/**
 * 视频流控制仓库
 *
 * 负责通过 SSH 控制 C3 端的 WebRTC 摄像头流开关。
 *
 * 说明：
 * - 直接写 /data/params/d/ 下的参数文件，避免依赖 C3 系统 Python（无 zmq 模块）。
 * - 同时写入 WebrtcStreamEnabled + IsDriverViewEnabled 两个参数：
 *   - WebrtcStreamEnabled：拉起 stream_encoderd + webrtcd 进程
 *   - IsDriverViewEnabled：拉起 camerad（摄像头驱动），offroad 也生效
 * - 两个参数均通过文件直接写入，绕过 params_pyx 白名单限制。
 */
class VideoStreamRepository(
    private val sshManager: SshManager
) {
    /** 开启 C3 端 WebRTC 摄像头流 */
    suspend fun enableWebrtcStream(): Result<Unit> =
        setWebrtcStream(true)

    /** 关闭 C3 端 WebRTC 摄像头流（省电，停止硬件编码与网络传输） */
    suspend fun disableWebrtcStream(): Result<Unit> =
        setWebrtcStream(false)

    private suspend fun setWebrtcStream(enabled: Boolean): Result<Unit> {
        val valStr = if (enabled) "1" else "0"
        // 直接写文件，不依赖 Python/zmq 环境
        // WebrtcStreamEnabled: 控制 stream_encoderd + webrtcd
        // IsDriverViewEnabled: 控制 camerad（摄像头驱动，offroad 也需开启）
        val cmd = "echo -n '$valStr' > /data/params/d/WebrtcStreamEnabled && " +
                  "echo -n '$valStr' > /data/params/d/IsDriverViewEnabled"
        return sshManager.executeCommand(cmd).map { }
    }

    /** 读取当前 WebRTC 流开关状态 */
    suspend fun isWebrtcStreamEnabled(): Result<Boolean> {
        return sshManager.executeCommand(
            "cat /data/params/d/WebrtcStreamEnabled 2>/dev/null || echo 0"
        ).map { it.trim() == "1" }
    }
}
