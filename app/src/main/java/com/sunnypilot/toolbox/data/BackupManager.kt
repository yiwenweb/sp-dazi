package com.sunnypilot.toolbox.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.sunnypilot.toolbox.model.AuthType
import com.sunnypilot.toolbox.model.ConnectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 一键备份/恢复管理器。
 *
 * 备份内容：
 *  - Room 数据库文件 (drive_stats + quick_commands)
 *  - DataStore 连接配置 (connection_config)
 *  - SharedPreferences DPI 设置
 *
 * 输出：ZIP 文件，包含原始 DB 文件 + JSON 配置文件。
 */
object BackupManager {

    private const val TAG = "BackupManager"

    private const val FILE_DB = "toolbox_database"
    private const val FILE_CONNECTION_CONFIG = "connection_config.json"
    private const val FILE_DPI_SETTINGS = "dpi_settings.json"
    private const val FILE_MANIFEST = "manifest.json"

    /**
     * 创建完整备份 ZIP。
     *
     * @param context Android Context
     * @param dbPath Room 数据库文件绝对路径（如 /data/data/.../databases/toolbox_database）
     * @param connectionConfig 当前连接配置（从 DataStore 读取）
     * @param onProgress 进度回调
     */
    suspend fun createBackup(
        context: Context,
        dbPath: String,
        connectionConfig: ConnectionConfig,
        onProgress: (String) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val zipFile = File(context.cacheDir, "sp_toolbox_backup_$timestamp.zip")

            onProgress("正在备份数据库…")
            closeDatabase()

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // 1. 备份 Room 数据库原始文件
                val dbFile = File(dbPath)
                if (dbFile.exists()) {
                    zos.putNextEntry(ZipEntry(FILE_DB))
                    FileInputStream(dbFile).use { it.copyTo(zos) }
                    zos.closeEntry()
                }

                // 2. 备份连接配置（JSON，不含密码/私钥）
                onProgress("正在备份连接配置…")
                zos.putNextEntry(ZipEntry(FILE_CONNECTION_CONFIG))
                val connJson = connectionConfigToJson(connectionConfig)
                zos.write(connJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 3. 备份 DPI 设置
                onProgress("正在备份DPI设置…")
                zos.putNextEntry(ZipEntry(FILE_DPI_SETTINGS))
                val dpiJson = exportDpiSettings(context)
                zos.write(dpiJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                // 4. manifest
                zos.putNextEntry(ZipEntry(FILE_MANIFEST))
                val manifest = JSONObject().apply {
                    put("version", 1)
                    put("timestamp", timestamp)
                    put("app", "SunnyPilot Toolbox")
                }
                zos.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            reopenDatabase(context)
            Result.success(zipFile)
        } catch (e: Exception) {
            Log.e(TAG, "备份失败", e)
            reopenDatabase(context)
            Result.failure(e)
        }
    }

    /**
     * 从 ZIP 备份文件恢复所有数据。
     *
     * @param configRepository 用于恢复连接配置
     */
    suspend fun restoreFromBackup(
        context: Context,
        zipUri: Uri,
        dbPath: String,
        configRepository: ConnectionConfigRepository,
        onProgress: (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            var dbRestored = false
            var configRestored = false
            var dpiRestored = false

            val tempFile = File(context.cacheDir, "restore_temp.zip")
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                FileOutputStream(tempFile).use { input.copyTo(it) }
            } ?: return@withContext Result.failure(Exception("无法读取备份文件"))

            closeDatabase()

            ZipFile(tempFile).use { zip ->
                // 恢复数据库
                val dbEntry = zip.getEntry(FILE_DB)
                if (dbEntry != null) {
                    onProgress("正在恢复数据库…")
                    zip.getInputStream(dbEntry).use { input ->
                        FileOutputStream(File(dbPath)).use { input.copyTo(it) }
                    }
                    dbRestored = true
                }

                // 恢复连接配置（通过 Repository 写入 DataStore）
                val connEntry = zip.getEntry(FILE_CONNECTION_CONFIG)
                if (connEntry != null) {
                    onProgress("正在恢复连接配置…")
                    val json = zip.getInputStream(connEntry).bufferedReader(Charsets.UTF_8).readText()
                    jsonToConnectionConfig(json)?.let { config ->
                        configRepository.save(config)
                        configRestored = true
                    }
                }

                // 恢复 DPI
                val dpiEntry = zip.getEntry(FILE_DPI_SETTINGS)
                if (dpiEntry != null) {
                    onProgress("正在恢复DPI设置…")
                    val json = zip.getInputStream(dpiEntry).bufferedReader(Charsets.UTF_8).readText()
                    importDpiSettings(context, json)
                    dpiRestored = true
                }
            }

            tempFile.delete()
            reopenDatabase(context)

            val msg = buildString {
                append("恢复完成：\n")
                if (dbRestored) append("✓ 数据库已恢复\n")
                if (configRestored) append("✓ 连接配置已恢复\n")
                if (dpiRestored) append("✓ DPI设置已恢复\n")
                append("\n建议重启应用使设置生效。")
            }
            Result.success(msg)
        } catch (e: Exception) {
            Log.e(TAG, "恢复失败", e)
            reopenDatabase(context)
            Result.failure(e)
        }
    }

    /** 分享备份文件 */
    fun shareBackup(context: Context, backupFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", backupFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "SunnyPilot Toolbox 数据备份")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "保存备份文件"))
        } catch (e: Exception) {
            Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── 内部工具 ──

    private fun connectionConfigToJson(config: ConnectionConfig): String {
        return JSONObject().apply {
            put("host", config.host)
            put("port", config.port)
            put("username", config.username)
            put("auth_type", config.authType.name)
            put("auto_connect", config.autoConnect)
            put("saved_key_file_name", config.savedKeyFileName)
            // 密码和私钥不导出
        }.toString(2)
    }

    private fun jsonToConnectionConfig(jsonStr: String): ConnectionConfig? {
        return try {
            val json = JSONObject(jsonStr)
            ConnectionConfig(
                host = json.optString("host", ""),
                port = json.optInt("port", 22),
                username = json.optString("username", SshManager.DEFAULT_USER),
                authType = try {
                    AuthType.valueOf(json.optString("auth_type", "PASSWORD"))
                } catch (_: Exception) { AuthType.PASSWORD },
                password = "",
                privateKeyContent = "",
                savedKeyFileName = json.optString("saved_key_file_name", ""),
                autoConnect = json.optBoolean("auto_connect", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析连接配置失败", e)
            null
        }
    }

    private fun exportDpiSettings(context: Context): String {
        val prefs = context.getSharedPreferences("dpi_settings", Context.MODE_PRIVATE)
        val scale = prefs.getFloat("density_scale", 1.0f)
        return JSONObject().apply { put("density_scale", scale.toDouble()) }.toString(2)
    }

    private fun importDpiSettings(context: Context, jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val scale = json.optDouble("density_scale", 1.0).toFloat()
            context.getSharedPreferences("dpi_settings", Context.MODE_PRIVATE)
                .edit().putFloat("density_scale", scale).apply()
        } catch (e: Exception) {
            Log.e(TAG, "导入DPI失败", e)
        }
    }

    private fun closeDatabase() {
        try {
            val field = Class.forName("com.sunnypilot.toolbox.data.db.AppDatabase")
                .getDeclaredField("INSTANCE")
            field.isAccessible = true
            val db = field.get(null) as? androidx.room.RoomDatabase
            db?.close()
            field.set(null, null)
        } catch (_: Exception) {}
    }

    private fun reopenDatabase(context: Context) {
        try {
            com.sunnypilot.toolbox.data.db.AppDatabase.getDatabase(context)
        } catch (_: Exception) {}
    }
}
