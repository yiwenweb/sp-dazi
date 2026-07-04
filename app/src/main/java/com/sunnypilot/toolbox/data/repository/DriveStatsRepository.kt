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

class DriveStatsRepository(context: Context, private val sshManager: SshManager) {
    private val dao: DriveStatsDao = AppDatabase.getDatabase(context).driveStatsDao()


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
        // TODO: 后续对接 C3 qlog 真实解析，当前先同步 segment 列表作为占位
        val result = sshManager.executeCommand(
            "ls /data/media/0/realdata/ 2>/dev/null | head -20 || echo ''"
        )
        result.map { 0 }
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
                stats.add(parseDriveStats(obj))
            }
            dao.insertAll(stats)
            Result.success(stats.size)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun insertSampleData() = withContext(Dispatchers.IO) {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val today = Date()
        val cal = Calendar.getInstance()
        val sample = List(30) { index ->
            cal.time = today
            cal.add(Calendar.DAY_OF_YEAR, -index)
            val date = fmt.format(cal.time)
            val total = (20f + kotlin.random.Random.nextFloat() * 80f)
            val assisted = total * (0.3f + kotlin.random.Random.nextFloat() * 0.5f)
            DriveStats(
                date = date,
                totalDistanceKm = total,
                assistedDistanceKm = assisted,
                manualDistanceKm = total - assisted,
                durationMinutes = (total * 1.2f).toInt(),
                takeovers = kotlin.random.Random.nextInt(0, 20),
                collisionWarning = kotlin.random.Random.nextInt(0, 3),
                tailgating = kotlin.random.Random.nextInt(0, 10),
                leadCarStationary = kotlin.random.Random.nextInt(0, 30),
                leadCarEmergencyBrake = kotlin.random.Random.nextInt(0, 8),
                leadCarSlow = kotlin.random.Random.nextInt(0, 40),
                startReminder = kotlin.random.Random.nextInt(0, 3),
                laneChangeAssist = kotlin.random.Random.nextInt(0, 15),
                safetyScore = 60 + kotlin.random.Random.nextInt(0, 35)
            )
        }
        dao.insertAll(sample)
    }

    private fun parseDriveStats(obj: JSONObject): DriveStats {
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

    companion object {
        fun dateRange(days: Int): Pair<String, String> {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val end = Date()
            val cal = Calendar.getInstance().apply { time = end; add(Calendar.DAY_OF_YEAR, -days + 1) }
            return fmt.format(cal.time) to fmt.format(end)
        }
    }
}
