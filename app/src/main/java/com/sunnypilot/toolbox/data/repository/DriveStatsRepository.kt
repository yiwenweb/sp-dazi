package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.net.Uri
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
 * 驾驶统计数据 Repository。
 *
 * 数据来源：通过 SSH 调用 C3 端的 calc_drive_stats.py，
 * 从 qlog 中解析真实行驶数据并写入本地 Room 数据库。
 */
class DriveStatsRepository(context: Context, private val sshManager: SshManager) {
    private val dao: DriveStatsDao = AppDatabase.getDatabase(context).driveStatsDao()

    /**
     * C3 端统计脚本路径（也复制到 /data/openpilot/c3_scripts/ 下同步用）。
     */
    companion object {
        private const val C3_SCRIPT = "c3_scripts/calc_drive_stats.py"
        private const val C3_REALDATA = "/data/media/0/realdata"

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

    suspend fun syncFromDevice(
        onStage: (String, SyncStatus) -> Unit = { _, _ -> }
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!sshManager.isConnected()) {
            return@withContext Result.failure(IllegalStateException("未连接 C3"))
        }

        // Step 1: 确保脚本存在
        onStage("正在连接 C3…", SyncStatus.CONNECTING)
        val checkScript = sshManager.executeCommand(
            "test -f /data/openpilot/${C3_SCRIPT} && echo 'OK' || echo 'MISSING'"
        ).getOrElse { return@withContext Result.failure(Exception("SSH 通信失败")) }

        if (checkScript.trim() == "MISSING") {
            return@withContext Result.failure(
                IllegalStateException("C3 上缺少 ${C3_SCRIPT}，请先部署脚本到 /data/openpilot/c3_scripts/")
            )
        }

        // Step 2: 检查是否有 segment 数据
        onStage("正在检查数据…", SyncStatus.CHECKING)
        val hasData = sshManager.executeCommand(
            "ls ${C3_REALDATA}/*--* 2>/dev/null | head -1 || echo ''"
        ).getOrElse { "" }

        if (hasData.trim().isEmpty()) {
            return@withContext Result.success(0)
        }

        // Step 3: 执行统计脚本
        onStage("正在远程计算统计（约 30~60 秒）…", SyncStatus.COMPUTING)
        val rawOutput = sshManager.executeCommand(
            "/usr/local/venv/bin/python /data/openpilot/${C3_SCRIPT}"
        ).getOrElse {
            return@withContext Result.failure(Exception("脚本执行失败: ${it.message}"))
        }

        // Step 4: 从输出中提取 JSON 数组
        onStage("正在解析数据…", SyncStatus.PARSING)
        val jsonStr = runCatching {
            val t = rawOutput.trim()
            val start = t.indexOf('[')
            val end = t.lastIndexOf(']')
            if (start < 0 || end <= start) throw Exception("输出中未找到 JSON 数组")
            t.substring(start, end + 1)
        }.getOrElse {
            return@withContext Result.failure(
                Exception("C3 脚本输出异常。原始输出:\n${rawOutput.take(500)}")
            )
        }

        // Step 5: 解析 JSON → 保存到本地
        onStage("正在保存到本地数据库…", SyncStatus.SAVING)
        runCatching {
            val array = JSONArray(jsonStr)
            if (array.length() == 0) {
                return@withContext Result.success(0)
            }

            val stats = mutableListOf<DriveStats>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                stats.add(parseJsonToDriveStats(obj))
            }

            dao.clear()
            dao.insertAll(stats)

            return@withContext Result.success(stats.size)
        }.onFailure { e ->
            return@withContext Result.failure(
                Exception("解析统计结果失败: ${e.message}\n原始输出:\n${rawOutput.take(500)}")
            )
        }
    }

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
