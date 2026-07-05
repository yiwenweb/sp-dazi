package com.sunnypilot.toolbox.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "quick_commands")
data class QuickCommand(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val command: String,
    val description: String = "",
    val sortOrder: Int = 0
)
