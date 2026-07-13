package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.db.AppDatabase
import com.sunnypilot.toolbox.data.db.DriveStatsDao
import com.sunnypilot.toolbox.data.sync.SyncStatus
import com.sunnypilot.toolbox.model.AggregatedStats
import com.sunnypilot.toolbox.model.DriveStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 驾驶统计数据 Repository (v3)。
 *
 * 数据来源：通过 SSH 调用 C3 端的 calc_drive_stats.py（从 assets 自动部署），
 * 从 qlog 中解析真实行驶数据：
 *   - 脚本存于 /data/openpilot/c3_scripts/calc_drive_stats.py
 *   - 增量标记：/data/appdata/last_sync.txt（C3 端记录上次扫描位置）
 *   - 结果缓存：/data/appdata/drive_stats.json（C3 端累积数据）
 *   - App 本地：Room 数据库增量合并
 */
class DriveStatsRepository(private val context: Context, private val sshManager: SshManager) {
    private val dao: DriveStatsDao = AppDatabase.getDatabase(context).driveStatsDao()

    companion object {
        private const val TAG = "DriveStatsRepository"

        /** C3 端脚本相对路径（相对于 /data/openpilot/） */
        private const val C3_SCRIPT = "c3_scripts/calc_drive_stats.py"

        /** C3 端脚本绝对路径 */
        const val REMOTE_SCRIPT = "/data/openpilot/c3_scripts/calc_drive_stats.py"

        /** C3 端应用数据目录 */
        const val APP_DATA_DIR = "/data/appdata"

        private const val C3_REALDATA = "/data/media/0/realdata"
        private const val C3_OPENPILOT = "/data/openpilot"

        fun dateRange(days: Int): Pair<String, String> {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val end = Date()
            val cal = Calendar.getInstance().apply {
                time = end
                add(Calendar.DAY_OF_YEAR, -days + 1)
            }
            return fmt.format(cal.time) to fmt.format(end)
        }
    }

    // ── 脚本部署 ──────────────────────────────────────────────

    /** 部署 calc_drive_stats.py 到 C3（从 assets 读取内容） */
    suspend fun deployScript(): Result<Unit> {
        return try {
            val content = context.assets.open("calc_drive_stats.py")
                .bufferedReader().use { it.readText() }
            // 确保目录存在
            sshManager.executeCommand("mkdir -p /data/openpilot/c3_scripts /data/appdata")
            sshManager.writeTextFile(REMOTE_SCRIPT, content).map { }
        } catch (e: Exception) {
            Result.failure(Exception("读取脚本失败: ${e.message}"))
        }
    }

    /** 检查脚本是否已部署到 C3 */
    suspend fun isScriptDeployed(): Result<Boolean> {
        return sshManager.executeCommand("[ -f $REMOTE_SCRIPT ] && echo 1 || echo 0")
            .map { it.trim() == "1" }
    }

    /** 检查脚本是否正在 C3 上运行 */
    suspend fun isScriptRunning(): Result<Boolean> {
        return sshManager.executeCommand("pgrep -f calc_drive_stats | wc -l")
            .map { (it.trim().toIntOrNull() ?: 0) > 0 }
    }

    // ── 数据查询 ──────────────────────────────────────────────

    suspend fun getAll(): List<DriveStats> = withContext(Dispatchers.IO) {
        dao.getAll()
    }

    suspend fun getBetween(start: String, end: String): List<DriveStats> = withContext(Dispatchers.IO) {
        dao.getBetween(start, end)
    }

    /** 检查单条记录是否物理上合理（防止损坏数据污染统计）*/
    private fun isPlausible(s: DriveStats): Boolean {
        val durH = s.durationMinutes / 60f
        return !(durH > 0 && s.totalDistanceKm / durH > 150f)
    }

    suspend fun aggregate(start: String, end: String): AggregatedStats = withContext(Dispatchers.IO) {
        val raw = dao.getBetween(start, end)
        val list = raw.filter { isPlausible(it) }
        val total = kotlin.math.round(list.sumOf { it.totalDistanceKm.toDouble() } * 10.0).toFloat() / 10f
        val assisted = kotlin.math.round(list.sumOf { it.assistedDistanceKm.toDouble() } * 10.0).toFloat() / 10f
        val manual = kotlin.math.round(list.sumOf { it.manualDistanceKm.toDouble() } * 10.0).toFloat() / 10f
        val duration = list.sumOf { it.durationMinutes }
        val assistedDuration = list.sumOf { it.assistedDurationMinutes }
        val takeovers = list.sumOf { it.takeovers }
        val collision = list.sumOf { it.collisionWarning }
        val tailgating = list.sumOf { it.tailgating }
        val leadStationary = list.sumOf { it.leadCarStationary }
        val leadBrake = list.sumOf { it.leadCarEmergencyBrake }
        val leadSlow = list.sumOf { it.leadCarSlow }
        val startReminder = list.sumOf { it.startReminder }
        val laneChange = list.sumOf { it.laneChangeAssist }
        val assistedPercent = if (total > 0) (assisted / total * 100).toInt() else 0
        val manualPercent = if (total > 0) (manual / total * 100).toInt() else 0
        val durationRatioPercent = if (duration > 0) (assistedDuration.toFloat() / duration * 100).toInt() else 0
        val score = calculateScore(list)
        val avgSpeed = if (duration > 0) {
            kotlin.math.round(total / (duration / 60f) * 10f) / 10f
        } else 0f
        val maxSpeed = if (list.isNotEmpty()) {
            kotlin.math.round(list.maxOf { it.maxSpeedKmh } * 10f) / 10f
        } else 0f
        val longestSingle = if (list.isNotEmpty()) {
            kotlin.math.round(list.maxOf { it.longestDistanceKm } * 10f) / 10f
        } else 0f
        val longestSegment = if (list.isNotEmpty()) list.maxOf { it.longestSegmentMinutes } else 0
        val takeoversPerKkm = if (total > 0) {
            kotlin.math.round(takeovers / (total / 1000f) * 100f) / 100f
        } else 0f
        AggregatedStats(
            startDate = start,
            totalDistanceKm = total,
            assistedDistanceKm = assisted,
            manualDistanceKm = manual,
            assistedDurationMinutes = assistedDuration,
            assistedPercent = assistedPercent,
            manualPercent = manualPercent,
            durationMinutes = duration,
            durationRatioPercent = durationRatioPercent,
            safetyScore = score,
            takeovers = takeovers,
            collisionWarning = collision,
            tailgating = tailgating,
            leadCarStationary = leadStationary,
            leadCarEmergencyBrake = leadBrake,
            leadCarSlow = leadSlow,
            startReminder = startReminder,
            laneChangeAssist = laneChange,
            longestSingleDistanceKm = longestSingle,
            continuousOpMinutes = longestSegment,
            takeoversPerKkm = takeoversPerKkm,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = maxSpeed
        )
    }

    private fun calculateScore(list: List<DriveStats>): Int {
        if (list.isEmpty()) return 0
        val total = list.sumOf { it.totalDistanceKm.toDouble() }.toFloat()
        val takeovers = list.sumOf { it.takeovers }
        val warnings = list.sumOf {
            it.collisionWarning + it.tailgating + it.leadCarEmergencyBrake +
                    it.leadCarSlow + it.startReminder + it.laneChangeAssist
        }
        var score = 100
        if (total > 0) {
            score -= (takeovers / (total / 1000f) * 2).toInt()
            score -= (warnings / (total / 100f)).toInt()
        }
        return score.coerceIn(0, 100)
    }

    // ── 同步（v4：本地空→读C3缓存优先，避免重装后重复计算） ──

    /** C3 端缓存文件路径 */
    private val C3_CACHE_FILE = "$APP_DATA_DIR/drive_stats.json"

    /** 检查本地数据库是否为空 */
    private suspend fun isDbEmpty(): Boolean = withContext(Dispatchers.IO) {
        dao.getEarliestDate() == null
    }

    /**
     * 从 C3 缓存文件直接加载全量统计数据（无需重新计算，秒级）。
     * 用于重装 App / 清数据后快速恢复。
     */
    private suspend fun loadCachedStatsFromC3(): Result<Int> {
        val cached = sshManager.readFile(C3_CACHE_FILE).getOrElse {
            return Result.failure(it)
        }
        if (cached.isBlank()) return Result.success(0)

        return runCatching {
            val array = JSONArray(cached)
            val stats = mutableListOf<DriveStats>()
            for (i in 0 until array.length()) {
                stats.add(parseJsonToDriveStats(array.getJSONObject(i)))
            }
            dao.insertAll(stats)
            Log.i(TAG, "从 C3 缓存恢复 ${stats.size} 天历史数据")
            stats.size
        }
    }

    /**
     * 从 C3 增量同步驾驶统计数据。
     *
     * 智能流程：
     *   1. 确保脚本已部署（缺失则自动部署）
     *   2. 本地 DB 为空 + C3 有缓存 → 直接读缓存恢复（秒级），再增量追新数据
     *   3. 本地 DB 为空 + C3 无缓存 → 全量扫描（首次使用）
     *   4. 本地 DB 有数据 → 增量同步（仅新 segment）
     */
    suspend fun syncFromDevice(
        onStage: (String, SyncStatus) -> Unit = { _, _ -> },
        forceFull: Boolean = false
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!sshManager.isConnected()) {
            return@withContext Result.failure(IllegalStateException("未连接 C3"))
        }

        // Step 1: 确保脚本存在 → 自动部署
        onStage("正在检查脚本…", SyncStatus.CONNECTING)
        val deployed = isScriptDeployed().getOrDefault(false)
        if (!deployed) {
            onStage("正在部署统计脚本到 C3…", SyncStatus.CONNECTING)
            deployScript().getOrElse {
                return@withContext Result.failure(Exception("脚本部署失败: ${it.message}"))
            }
            Log.i(TAG, "calc_drive_stats.py 已自动部署到 C3")
        }

        // Step 2: 智能判断同步策略
        val dbEmpty = isDbEmpty()

        // ── 策略A：本地为空 + C3 有缓存 → 直接读缓存恢复历史数据 ──
        if (dbEmpty && !forceFull) {
            onStage("正在从 C3 缓存恢复数据…", SyncStatus.CHECKING)
            val hasCache = sshManager.executeCommand(
                "[ -f $C3_CACHE_FILE ] && echo 1 || echo 0"
            ).getOrDefault("0").trim() == "1"

            if (hasCache) {
                onStage("正在加载历史数据（秒级）…", SyncStatus.PARSING)
                val cachedCount = loadCachedStatsFromC3().getOrElse { 0 }
                Log.i(TAG, "从 C3 缓存恢复 $cachedCount 天数据")

                // 缓存恢复后，再跑一次增量同步追最新数据
                onStage("正在检查新数据…", SyncStatus.COMPUTING)
                val rawOutput = sshManager.executeCommand(
                    "cd $C3_OPENPILOT && /usr/local/venv/bin/python $REMOTE_SCRIPT --incremental"
                ).getOrElse {
                    // 增量失败不影响已有数据
                    Log.w(TAG, "增量同步失败: ${it.message}")
                    return@withContext Result.success(cachedCount)
                }
                val merged = mergeStatsFromOutput(rawOutput)
                val total = if (merged > 0) cachedCount + merged else cachedCount
                return@withContext Result.success(total)
            }
            // 无缓存 → 降级为全量扫描
            Log.i(TAG, "C3 无缓存，执行全量扫描")
        }

        // ── 策略B：正常增量 / 全量扫描 ──
        // Step 3: 检查是否有 segment 数据
        onStage("正在检查数据…", SyncStatus.CHECKING)
        val hasData = sshManager.executeCommand(
            "ls ${C3_REALDATA}/*--* 2>/dev/null | head -1 || echo ''"
        ).getOrElse { "" }

        if (hasData.trim().isEmpty()) {
            return@withContext Result.success(0)
        }

        // Step 4: 执行统计脚本
        val flag = if (forceFull || dbEmpty) "--full" else "--incremental"
        val stageMsg = if (forceFull || dbEmpty) "正在全量统计（约 1~3 分钟）…" else "正在增量统计（约 5~30 秒）…"
        onStage(stageMsg, SyncStatus.COMPUTING)
        val rawOutput = sshManager.executeCommand(
            "cd $C3_OPENPILOT && /usr/local/venv/bin/python $REMOTE_SCRIPT $flag"
        ).getOrElse {
            return@withContext Result.failure(Exception("脚本执行失败: ${it.message}"))
        }

        // Step 5: 解析 JSON → 合并保存
        val count = mergeStatsFromOutput(rawOutput)
        return@withContext Result.success(count)
    }

    /** 从脚本 stdout 提取 JSON 并合并到本地数据库，返回记录数 */
    private fun mergeStatsFromOutput(rawOutput: String): Int {
        val jsonStr = runCatching {
            val t = rawOutput.trim()
            val start = t.indexOf('[')
            val end = t.lastIndexOf(']')
            if (start < 0 || end <= start) throw Exception("输出中未找到 JSON 数组")
            t.substring(start, end + 1)
        }.getOrElse {
            Log.w(TAG, "解析脚本输出失败: ${it.message}")
            return 0
        }

        val array = JSONArray(jsonStr)
        if (array.length() == 0) return 0

        val stats = mutableListOf<DriveStats>()
        for (i in 0 until array.length()) {
            stats.add(parseJsonToDriveStats(array.getJSONObject(i)))
        }
        dao.insertAll(stats)
        Log.i(TAG, "合并保存 ${stats.size} 天数据到本地")
        return stats.size
    }

    // ── 仅部署脚本（供 UI 手动触发） ──────────────────────────

    /** 部署脚本并返回是否成功（与上面 deployScript 相同，命名更语义化） */
    suspend fun deployScriptToC3(): Result<Unit> = deployScript()

    // ── JSON 解析 ─────────────────────────────────────────────

    private fun parseJsonToDriveStats(obj: JSONObject): DriveStats {
        return DriveStats(
            date = obj.getString("date"),
            totalDistanceKm = obj.optDouble("totalDistanceKm", 0.0).toFloat(),
            assistedDistanceKm = obj.optDouble("assistedDistanceKm", 0.0).toFloat(),
            manualDistanceKm = obj.optDouble("manualDistanceKm", 0.0).toFloat(),
            durationMinutes = obj.optInt("durationMinutes", 0),
            assistedDurationMinutes = obj.optInt("assistedDurationMinutes", 0),
            maxSpeedKmh = obj.optDouble("maxSpeedKmh", 0.0).toFloat(),
            longestDistanceKm = obj.optDouble("maxSegmentDistanceKm", 0.0).toFloat(),
            longestSegmentMinutes = obj.optInt("longestSegmentMinutes", 0),
            takeovers = obj.optInt("takeovers", 0),
            collisionWarning = obj.optInt("collisionWarning", 0),
            tailgating = obj.optInt("tailgating", 0),
            leadCarStationary = obj.optInt("leadCarStationary", 0),
            leadCarEmergencyBrake = obj.optInt("leadCarEmergencyBrake", 0),
            leadCarSlow = obj.optInt("leadCarSlow", 0),
            startReminder = obj.optInt("startReminder", 0),
            laneChangeAssist = obj.optInt("laneChangeAssist", 0),
            safetyScore = obj.optInt("safetyScore", 0)
        )
    }

    // ── 导入 / 导出 ──────────────────────────────────────────

    suspend fun exportToJson(context: Context, start: String, end: String): Uri = withContext(Dispatchers.IO) {
        val list = dao.getBetween(start, end)
        val text = JSONArray(list.map { it.toJson() }).toString(2)
        val file = File(context.cacheDir, "drive_stats_${start}_${end}.json")
        FileWriter(file).use { it.write(text) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    suspend fun importFromJson(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext Result.failure(IllegalStateException("无法读取文件"))
            val array = JSONArray(text)
            val stats = mutableListOf<DriveStats>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                stats.add(parseJsonToDriveStats(obj))
            }
            dao.insertAll(stats)
            Result.success(stats.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
