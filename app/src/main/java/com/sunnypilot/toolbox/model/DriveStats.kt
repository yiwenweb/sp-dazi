package com.sunnypilot.toolbox.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject

@Entity(tableName = "drive_stats")
data class DriveStats(
    @PrimaryKey
    val date: String,
    val totalDistanceKm: Float = 0f,
    val assistedDistanceKm: Float = 0f,
    val manualDistanceKm: Float = 0f,
    val durationMinutes: Int = 0,
    val assistedDurationMinutes: Int = 0,
    val maxSpeedKmh: Float = 0f,
    val longestDistanceKm: Float = 0f,
    val longestSegmentMinutes: Int = 0,
    val takeovers: Int = 0,
    val collisionWarning: Int = 0,
    val tailgating: Int = 0,
    val leadCarStationary: Int = 0,
    val leadCarEmergencyBrake: Int = 0,
    val leadCarSlow: Int = 0,
    val startReminder: Int = 0,
    val laneChangeAssist: Int = 0,
    val safetyScore: Int = 0
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("date", date)
            put("totalDistanceKm", totalDistanceKm.toDouble())
            put("assistedDistanceKm", assistedDistanceKm.toDouble())
            put("manualDistanceKm", manualDistanceKm.toDouble())
            put("durationMinutes", durationMinutes)
            put("assistedDurationMinutes", assistedDurationMinutes)
            put("maxSpeedKmh", maxSpeedKmh.toDouble())
            put("longestDistanceKm", longestDistanceKm.toDouble())
            put("longestSegmentMinutes", longestSegmentMinutes)
            put("takeovers", takeovers)
            put("collisionWarning", collisionWarning)
            put("tailgating", tailgating)
            put("leadCarStationary", leadCarStationary)
            put("leadCarEmergencyBrake", leadCarEmergencyBrake)
            put("leadCarSlow", leadCarSlow)
            put("startReminder", startReminder)
            put("laneChangeAssist", laneChangeAssist)
            put("safetyScore", safetyScore)
        }
    }
}

data class AggregatedStats(
    val startDate: String,
    val totalDistanceKm: Float,
    val assistedDistanceKm: Float,
    val manualDistanceKm: Float,
    val assistedPercent: Int,
    val manualPercent: Int,
    val durationMinutes: Int,
    val assistedDurationMinutes: Int = 0,
    val durationRatioPercent: Int,
    val safetyScore: Int,
    val takeovers: Int,
    val collisionWarning: Int,
    val tailgating: Int,
    val leadCarStationary: Int,
    val leadCarEmergencyBrake: Int,
    val leadCarSlow: Int,
    val startReminder: Int,
    val laneChangeAssist: Int,
    val longestSingleDistanceKm: Float = 0f,
    val continuousOpMinutes: Int = 0,
    val takeoversPerKkm: Float = 0f,
    val avgSpeedKmh: Float = 0f,
    val maxSpeedKmh: Float = 0f
)
