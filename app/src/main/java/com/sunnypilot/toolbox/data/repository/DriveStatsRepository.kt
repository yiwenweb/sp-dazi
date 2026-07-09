package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.db.AppDatabase
import com.sunnypilot.toolbox.data.db.DriveStatsDao
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

    suspend fun aggregate(start: String, end: String): AggregatedStats = withContext(Dispatchers.IO) {
        val list = dao.getBetween(start, end)
        val total = list.sumOf { it.totalDistanceKm.toDouble() }.toFloat()
        val assisted = list.sumOf { it.assistedDistanceKm.toDouble() }.toFloat()
        val manual = list.sumOf { it.manualDistanceKm.toDouble() }.toFloat()
        val duration = list.sumOf { it.durationMinutes }
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
        val durationRatioPercent = 14
        val score = calculateScore(list)
        val avgSpeed = if (duration > 0) total / (duration / 60f) else 0f
        val maxSpeed = if (list.isNotEmpty()) list.maxOf { it.totalDistanceKm } else 0f
        val takeoversPerKkm = if (total > 0) takeovers / (total / 1000f) else 0f
        AggregatedStats(
            startDate = start,
            totalDistanceKm = total,
            assistedDistanceKm = assisted,
            manualDistanceKm = manual,
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
            longestSingleDistanceKm = maxSpeed,
            continuousOpMinutes = duration / maxOf(list.size, 1),
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

    suspend fun syncFromDevice(): Result<Int> = withContext(Dispatchers.IO) {
        if (!sshManager.isConnected()) {
            return@withContext Result.failure(IllegalStateException("未连接 C3"))
        }

        // Step 1: 确保脚本存在（若缺失则尝试从 /data/openpilot/ 复制）
        val checkScript = sshManager.executeCommand(
            "test -f /data/openpilot/${C3_SCRIPT} && echo 'OK' || echo 'MISSING'"
        ).getOrElse { return@withContext Result.failure(Exception("SSH 通信失败")) }

        if (checkScript.trim() == "MISSING") {
            return@withContext Result.failure(
                IllegalStateException("C3 上缺少 ${C3_SCRIPT}，请先部署脚本到 /data/openpilot/c3_scripts/")
            )
        }

        // Step 2: 检查是否有 segment 数据
        val hasData = sshManager.executeCommand(
            "ls ${C3_REALDATA}/*--* 2>/dev/null | head -1 || echo ''"
        ).getOrElse { "" }

        if (hasData.trim().isEmpty()) {
            // 无行车数据，返回 0 但不视为失败
            return@withContext Result.success(0)
        }

        // Step 3: 执行统计脚本，捕获输出
        // C3 上 openpilot 的 Python 依赖装在 /usr/local/venv/ 下（非系统 python3），
        // 必须用 venv 的 python 否则导入 LogReader（需 zstandard / capnp）会失败
        // 注意：SshManager 会自动合并 stdout+stderr，因此必须从混合输出中提取 JSON
        val rawOutput = sshManager.executeCommand(
            "/usr/local/venv/bin/python /data/openpilot/${C3_SCRIPT}"
        ).getOrElse {
            return@withContext Result.failure(Exception("脚本执行失败: ${it.message}"))
        }

        // Step 4: 从输出中提取 JSON 数组（免疫 stderr 后缀干扰）
        val jsonStr = runCatching {
            val t = rawOutput.trim()
            val start = t.indexOf('[')
            val end = t.lastIndexOf(']')
            if (start < 0 || end <= start) throw Exception("输出中未找到 JSON 数组")
            t.substring(start, end + 1)
        }.getOrElse {
            return@withContext Result.failure(
                Exception("解析统计结果失败。原始输出: ${rawOutput.take(300)}")
            )
        }

        // Step 5: 解析 JSON 数组 → DriveStats 列表
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

            // Step 6: 全量替换（清除旧数据，写入新数据）
            dao.clear()
            dao.insertAll(stats)

            return@withContext Result.success(stats.size)
        }.onFailure { e ->
            return@withContext Result.failure(
                Exception("解析统计结果失败: ${e.message}. 原始输出: ${rawOutput.take(300)}")
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
