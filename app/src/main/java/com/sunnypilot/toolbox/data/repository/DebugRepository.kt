package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.DebugEvent
import com.sunnypilot.toolbox.model.DebugStatus
import kotlinx.serialization.json.Json
import java.io.File

/**
 * DEBUG 抓包功能仓库。
 *
 * 架构: C3 上常驻守护脚本 byd_debug_watchdog.py 做黑匣子(环形缓冲+多故障检测+自动存证),
 *        本 App 只负责: 部署脚本 / 启停守护 / 查状态 / 列事件 / 下载分析。
 *
 * 守护约定的路径:
 *   /data/byd_debug_watchdog.py   守护脚本本体
 *   /data/byd_debug/enabled       开关标志文件(存在=监测开启)
 *   /data/byd_debug/status.json   守护心跳状态
 *   /data/byd_debug/events/<ts>_<fault>/  每个故障事件目录(event.json + dump.jsonl)
 *   /data/byd_debug/watchdog.log  守护运行日志
 */
class DebugRepository(
    private val context: Context,
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "DebugRepository"
        const val REMOTE_SCRIPT = "/data/byd_debug_watchdog.py"
        const val BASE_DIR = "/data/byd_debug"
        const val ENABLED_FLAG = "$BASE_DIR/enabled"
        const val STATUS_JSON = "$BASE_DIR/status.json"
        const val EVENTS_DIR = "$BASE_DIR/events"
        const val LOG_FILE = "$BASE_DIR/watchdog.log"
        private const val OPENPILOT = "/data/openpilot"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun cacheDir(): File = File(context.cacheDir, "debug").apply { mkdirs() }

    /** 守护进程是否在运行 */
    suspend fun isRunning(): Result<Boolean> {
        return sshManager.executeCommand("pgrep -f byd_debug_watchdog | wc -l")
            .map { it.trim().toIntOrNull() ?: 0 }
            .map { it > 0 }
    }

    /**
     * 部署守护脚本到 C3 (若本地已上传副本则覆盖)。
     * App 内不打包脚本文件, 这里通过 writeTextFile 写入脚本内容。
     * scriptContent 由调用方(assets 或内嵌字符串)提供。
     */
    suspend fun deployScript(scriptContent: String): Result<Unit> {
        return sshManager.writeTextFile(REMOTE_SCRIPT, scriptContent).map { }
    }

    /** 脚本是否已部署 */
    suspend fun isScriptDeployed(): Result<Boolean> {
        return sshManager.executeCommand("[ -f $REMOTE_SCRIPT ] && echo 1 || echo 0")
            .map { it.trim() == "1" }
    }

    /**
     * 开启监测: 建目录 + 写 enabled 标志 + nohup 启动守护(脱离 SSH 会话常驻)。
     * 幂等: 已在跑则只补 enabled 标志。
     */
    suspend fun startWatchdog(): Result<Unit> {
        val cmd = buildString {
            append("mkdir -p $EVENTS_DIR && touch $ENABLED_FLAG && ")
            // 已在运行则不重复启动
            append("if pgrep -f byd_debug_watchdog > /dev/null; then echo already; else ")
            append("cd $OPENPILOT && source /usr/local/venv/bin/activate 2>/dev/null; ")
            append("export PYTHONPATH=$OPENPILOT && ")
            append("nohup python $REMOTE_SCRIPT > $LOG_FILE 2>&1 & echo started; fi")
        }
        return sshManager.executeCommand(cmd).map { }
    }

    /**
     * 关闭监测: 删 enabled 标志 + 杀守护进程。
     */
    suspend fun stopWatchdog(): Result<Unit> {
        return sshManager.executeCommand(
            "rm -f $ENABLED_FLAG; pkill -f byd_debug_watchdog; echo stopped"
        ).map { }
    }

    /** 读守护状态 (status.json + 进程存活 + enabled 标志) */
    suspend fun readStatus(): Result<DebugStatus> {
        val cmd = buildString {
            append("R=\$(pgrep -f byd_debug_watchdog | wc -l); ")
            append("A=\$([ -f $ENABLED_FLAG ] && echo 1 || echo 0); ")
            append("S=\$(cat $STATUS_JSON 2>/dev/null || echo '{}'); ")
            append("echo \"\$R|\$A|\$S\"")
        }
        return sshManager.executeCommand(cmd).mapCatching { output ->
            val line = output.trim()
            val idx1 = line.indexOf('|')
            val idx2 = line.indexOf('|', idx1 + 1)
            val running = line.substring(0, idx1).trim().toIntOrNull() ?: 0
            val active = line.substring(idx1 + 1, idx2).trim() == "1"
            val statusJson = line.substring(idx2 + 1).trim().ifBlank { "{}" }
            val base = try {
                json.decodeFromString(DebugStatus.serializer(), statusJson)
            } catch (e: Exception) {
                DebugStatus()
            }
            base.copy(running = running > 0, active = active)
        }
    }

    /** 列出所有已捕获事件 (读 events 目录 + 每个 event.json) */
    suspend fun listEvents(): Result<List<DebugEvent>> {
        // 每个事件目录输出: 目录名 + event.json 内容(单行) + 是否有dump, 用特殊分隔
        val cmd = buildString {
            append("for d in $EVENTS_DIR/*/; do ")
            append("[ -d \"\$d\" ] || continue; ")
            append("name=\$(basename \"\$d\"); ")
            append("hasdump=\$([ -f \"\${d}dump.jsonl\" ] && echo 1 || echo 0); ")
            append("ej=\$(cat \"\${d}event.json\" 2>/dev/null | tr -d '\\n' || echo '{}'); ")
            append("echo \"###\$name###\$hasdump###\$ej\"; done")
        }
        return sshManager.executeCommand(cmd).mapCatching { output ->
            output.lines().mapNotNull { raw ->
                val line = raw.trim()
                if (!line.startsWith("###")) return@mapNotNull null
                val parts = line.removePrefix("###").split("###", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val name = parts[0]
                val hasDump = parts[1] == "1"
                val ej = parts[2].ifBlank { "{}" }
                val ev = try {
                    json.decodeFromString(DebugEvent.serializer(), ej)
                } catch (e: Exception) {
                    DebugEvent(fault = "?", faultName = "解析失败")
                }
                ev.copy(dirName = name, hasDump = hasDump)
            }.sortedByDescending { it.triggerTime }
        }
    }

    /** 下载某事件的 dump.jsonl 到本地缓存, 返回本地文件 */
    suspend fun downloadDump(dirName: String): Result<File> {
        val local = File(cacheDir(), "$dirName/dump.jsonl")
        local.parentFile?.mkdirs()
        val remote = "$EVENTS_DIR/$dirName/dump.jsonl"
        return sshManager.downloadFile(remote, local).map { local }
    }

    /** 读某事件的 report.txt (若守护生成了) */
    suspend fun readReport(dirName: String): Result<String> {
        return sshManager.readFile("$EVENTS_DIR/$dirName/report.txt")
    }

    /** 删除某事件目录 */
    suspend fun deleteEvent(dirName: String): Result<Unit> {
        return sshManager.deleteRemote("$EVENTS_DIR/$dirName", isDir = true)
    }

    /** 读守护日志尾部(排障用) */
    suspend fun tailLog(lines: Int = 40): Result<String> {
        return sshManager.executeCommand("tail -n $lines $LOG_FILE 2>/dev/null || echo '(无日志)'")
    }
}
