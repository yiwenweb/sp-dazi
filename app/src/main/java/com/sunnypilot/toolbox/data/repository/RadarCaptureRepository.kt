package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.RadarCaptureStatus
import com.sunnypilot.toolbox.model.RadarMark
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 雷达距离标定采集 - C3 通信仓库。
 *
 * 方案1(卷尺对照法): 车静止, 前方摆已知距离目标, App 端逐个距离打点,
 * C3 脚本把雷达帧和精确距离绑定, 生成标定表, 离线解距离编码。
 *
 * C3 路径约定(与 byd_radar_capture.py 一致):
 *   /data/byd_radar_capture.py      抓取脚本
 *   /data/byd_radar/enabled         采集开关标志
 *   /data/byd_radar/mark            打点(写入距离数值触发)
 *   /data/byd_radar/status.json     心跳状态
 *   /data/byd_radar/marks/          各标定点 jsonl
 *   /data/byd_radar/capture.log     运行日志
 */
class RadarCaptureRepository(
    private val context: Context,
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "RadarCaptureRepo"
        const val REMOTE_SCRIPT = "/data/byd_radar_capture.py"
        const val BASE_DIR = "/data/byd_radar"
        const val ENABLED_FLAG = "$BASE_DIR/enabled"
        const val MARK_FILE = "$BASE_DIR/mark"
        const val STATUS_JSON = "$BASE_DIR/status.json"
        const val MARKS_DIR = "$BASE_DIR/marks"
        const val LOG_FILE = "$BASE_DIR/capture.log"
        private const val OPENPILOT = "/data/openpilot"
        private const val ASSET_SCRIPT = "byd_radar_capture.py"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun cacheDir(): File = File(context.cacheDir, "radar").apply { mkdirs() }

    /** 守护脚本是否在运行 */
    suspend fun isRunning(): Result<Boolean> =
        sshManager.executeCommand("pgrep -f byd_radar_capture | wc -l")
            .map { (it.trim().toIntOrNull() ?: 0) > 0 }

    /** 部署脚本(从 assets 写到 C3) */
    suspend fun deployScript(): Result<Unit> {
        return try {
            val content = context.assets.open(ASSET_SCRIPT)
                .bufferedReader().use { it.readText() }
            sshManager.writeTextFile(REMOTE_SCRIPT, content).map { }
        } catch (e: Exception) {
            Log.e(TAG, "读取脚本失败", e)
            Result.failure(e)
        }
    }

    suspend fun isScriptDeployed(): Result<Boolean> =
        sshManager.executeCommand("[ -f $REMOTE_SCRIPT ] && echo 1 || echo 0")
            .map { it.trim() == "1" }

    /** 开始采集: 部署(若无) + 建目录 + 写enabled + nohup启动 */
    suspend fun startCapture(): Result<Unit> {
        val deployed = isScriptDeployed().getOrDefault(false)
        if (!deployed) {
            val dep = deployScript()
            if (dep.isFailure) return dep
        }
        val cmd = buildString {
            append("mkdir -p $MARKS_DIR && touch $ENABLED_FLAG && ")
            append("if pgrep -f byd_radar_capture > /dev/null; then echo already; else ")
            append("cd $OPENPILOT && source /usr/local/venv/bin/activate 2>/dev/null; ")
            append("export PYTHONPATH=$OPENPILOT && ")
            append("nohup python $REMOTE_SCRIPT > $LOG_FILE 2>&1 & echo started; fi")
        }
        return sshManager.executeCommand(cmd).map { }
    }

    /** 停止采集: 删enabled + 杀进程 */
    suspend fun stopCapture(): Result<Unit> =
        sshManager.executeCommand("rm -f $ENABLED_FLAG; pkill -f byd_radar_capture; echo stopped").map { }

    /** 打点: 在当前距离(卷尺量的真实米数)记一个标定点 */
    suspend fun mark(distanceM: Float): Result<Unit> =
        sshManager.executeCommand("echo '$distanceM' > $MARK_FILE").map { }

    /** 读采集状态 */
    suspend fun readStatus(): Result<RadarCaptureStatus> {
        val cmd = buildString {
            append("R=\$(pgrep -f byd_radar_capture | wc -l); ")
            append("A=\$([ -f $ENABLED_FLAG ] && echo 1 || echo 0); ")
            append("S=\$(cat $STATUS_JSON 2>/dev/null || echo '{}'); ")
            append("echo \"\$R|\$A|\$S\"")
        }
        return sshManager.executeCommand(cmd).mapCatching { output ->
            val line = output.trim()
            val i1 = line.indexOf('|')
            val i2 = line.indexOf('|', i1 + 1)
            val running = line.substring(0, i1).trim().toIntOrNull() ?: 0
            val active = line.substring(i1 + 1, i2).trim() == "1"
            val statusJson = line.substring(i2 + 1).trim().ifBlank { "{}" }
            val base = try {
                json.decodeFromString(RadarCaptureStatus.serializer(), statusJson)
            } catch (e: Exception) {
                RadarCaptureStatus()
            }
            base.copy(running = running > 0, active = active)
        }
    }

    /** 列出已完成的标定点 */
    suspend fun listMarks(): Result<List<RadarMark>> {
        // 文件名格式: <序号>_<距离>m.jsonl, 行数=帧数
        val cmd = "for f in $MARKS_DIR/*.jsonl; do [ -f \"\$f\" ] || continue; " +
                "n=\$(wc -l < \"\$f\"); echo \"\$(basename \"\$f\")|\$n\"; done"
        return sshManager.executeCommand(cmd).mapCatching { output ->
            output.lines().mapNotNull { raw ->
                val line = raw.trim()
                if (line.isBlank() || !line.contains("|")) return@mapNotNull null
                val parts = line.split("|")
                val name = parts[0]                       // 01_20.0m.jsonl
                val frames = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                val base = name.removeSuffix(".jsonl")     // 01_20.0m
                val idx = base.substringBefore("_").toIntOrNull() ?: 0
                val dist = base.substringAfter("_").removeSuffix("m").toFloatOrNull() ?: 0f
                RadarMark(index = idx, distanceM = dist, fileName = name, frames = frames)
            }.sortedBy { it.index }
        }
    }

    /** 下载全部标定数据(打包marks目录为单个文件下载) */
    suspend fun downloadAllMarks(localFile: File): Result<File> {
        // 合并所有 marks 到一个文件再下载
        val merged = "$BASE_DIR/all_marks.jsonl"
        val cmd = "cat $MARKS_DIR/*.jsonl > $merged 2>/dev/null; wc -l < $merged"
        val r = sshManager.executeCommand(cmd)
        if (r.isFailure) return Result.failure(r.exceptionOrNull() ?: Exception("合并失败"))
        localFile.parentFile?.mkdirs()
        return sshManager.downloadFile(merged, localFile).map { localFile }
    }

    /** 清空所有标定点(重新开始) */
    suspend fun clearMarks(): Result<Unit> =
        sshManager.executeCommand("rm -f $MARKS_DIR/*.jsonl; echo cleared").map { }

    /** 读日志尾部 */
    suspend fun tailLog(lines: Int = 30): Result<String> =
        sshManager.executeCommand("tail -n $lines $LOG_FILE 2>/dev/null || echo '(无日志)'")

    fun localDownloadFile(): File = File(cacheDir(), "radar_marks.jsonl")
}
