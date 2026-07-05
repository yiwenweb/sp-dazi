package com.sunnypilot.toolbox.data.db

import androidx.room.*
import com.sunnypilot.toolbox.model.QuickCommand
import kotlinx.coroutines.flow.Flow

@Dao
interface QuickCommandDao {
    @Query("SELECT * FROM quick_commands ORDER BY sortOrder ASC, id ASC")
    fun getAll(): Flow<List<QuickCommand>>

    @Query("SELECT * FROM quick_commands ORDER BY sortOrder ASC, id ASC")
    fun getAllSync(): List<QuickCommand>

    @Query("SELECT * FROM quick_commands WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): QuickCommand?

    @Insert
    suspend fun insert(command: QuickCommand): Long

    @Update
    suspend fun update(command: QuickCommand)

    @Delete
    suspend fun delete(command: QuickCommand)
}
