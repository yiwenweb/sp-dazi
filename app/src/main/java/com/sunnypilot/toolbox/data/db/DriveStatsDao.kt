package com.sunnypilot.toolbox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sunnypilot.toolbox.model.DriveStats

@Dao
interface DriveStatsDao {
    @Query("SELECT * FROM drive_stats ORDER BY date DESC")
    suspend fun getAll(): List<DriveStats>

    @Query("SELECT * FROM drive_stats WHERE date BETWEEN :start AND :end ORDER BY date DESC")
    suspend fun getBetween(start: String, end: String): List<DriveStats>

    @Query("SELECT * FROM drive_stats WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DriveStats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stats: DriveStats)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stats: List<DriveStats>)

    @Query("DELETE FROM drive_stats WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM drive_stats")
    suspend fun clear()

    @Query("SELECT MAX(date) FROM drive_stats")
    suspend fun getLatestDate(): String?

    @Query("SELECT MIN(date) FROM drive_stats")
    suspend fun getEarliestDate(): String?
}
