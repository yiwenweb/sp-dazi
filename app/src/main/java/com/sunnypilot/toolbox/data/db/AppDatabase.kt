package com.sunnypilot.toolbox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sunnypilot.toolbox.model.DriveStats
import com.sunnypilot.toolbox.model.QuickCommand

@Database(
    entities = [DriveStats::class, QuickCommand::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun driveStatsDao(): DriveStatsDao
    abstract fun quickCommandDao(): QuickCommandDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS quick_commands (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        command TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        sortOrder INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drive_stats ADD COLUMN assistedDurationMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE drive_stats ADD COLUMN maxSpeedKmh REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE drive_stats ADD COLUMN longestDistanceKm REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE drive_stats ADD COLUMN longestSegmentMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "toolbox_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
