package com.sunnypilot.toolbox.data.repository

import com.sunnypilot.toolbox.data.SshManager

/**
 * 视频流控制仓库
 *
 * 负责通过 SSH 控制 C3 端的 WebRTC 摄像头流开关（WebrtcStreamEnabled 参数）。
 *
 * 说明：
 * - WebrtcStreamEnabled 是 openpilot 持久化参数（PERSISTENT | BACKUP），重启后保持。
 * - 该参数仅在 onroad（车辆启动）时才会真正拉起 stream_encoderd + webrtcd 进程。
 * - 通过 openpilot Params API 写入，保证与进程管理器一致的读写语义。
 */
class VideoStreamRepository(
    private val sshManager: SshManager
) {
    /** 开启 C3 端 WebRTC 摄像头流（写持久化参数，onroad 时生效） */
    suspend fun enableWebrtcStream(): Result<Unit> =
        setWebrtcStream(true)

    /** 关闭 C3 端 WebRTC 摄像头流（省电，停止硬件编码与网络传输） */
    suspend fun disableWebrtcStream(): Result<Unit> =
        setWebrtcStream(false)

    private suspend fun setWebrtcStream(enabled: Boolean): Result<Unit> {
        val py = "from openpilot.common.params import Params; " +
            "Params().put_bool('WebrtcStreamEnabled', ${if (enabled) "True" else "False"})"
        return sshManager.executeCommand(
            "cd /data/openpilot && python3 -c \"$py\""
        ).map { }
    }

    /** 读取当前 WebRTC 流开关状态 */
    suspend fun isWebrtcStreamEnabled(): Result<Boolean> {
        return sshManager.executeCommand(
            "cat /data/params/d/WebrtcStreamEnabled 2>/dev/null || echo 0"
        ).map { it.trim() == "1" }
    }
}
