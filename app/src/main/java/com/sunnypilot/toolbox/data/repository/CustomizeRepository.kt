package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CustomizeRepository(
    private val sshManager: SshManager
) {
    companion object {
        private const val TAG = "CustomizeRepository"

        // C3 路径常量
        const val SPINNER_IMAGE_PATH = "/data/openpilot/sunnypilot/selfdrive/assets/images/spinner_sunnypilot.png"
        const val SPINNER_BACKUP_PATH = "/data/openpilot/sunnypilot/selfdrive/assets/images/spinner_sunnypilot.png.bak"
        const val SOUNDS_DIR = "/data/openpilot/selfdrive/assets/sounds"
        const val VOLUME_PARAM_PATH = "/data/params/d/SoundVolume"

        // 声音文件列表
        val SOUND_FILES = listOf(
            SoundDef("engage", "接合提示", "engage.wav", "辅助驾驶接合时的提示音"),
            SoundDef("disengage", "解除提示", "disengage.wav", "辅助驾驶解除时的提示音"),
            SoundDef("prompt", "一般提示", "prompt.wav", "驾驶员注意力提醒"),
            SoundDef("prompt_distracted", "分心提示", "prompt_distracted.wav", "驾驶员分心警告"),
            SoundDef("refuse", "拒绝音", "refuse.wav", "操作被拒绝时的提示音"),
            SoundDef("warning_soft", "软警告", "warning_soft.wav", "轻度警告提示"),
            SoundDef("warning_immediate", "紧急警告", "warning_immediate.wav", "紧急立即警告"),
        )
    }

    data class SoundDef(
        val key: String,
        val name: String,
        val fileName: String,
        val description: String
    )

    /**
     * 检查 C3 上启动图片备份是否存在，用于判断当前是否为自定义图片
     */
    suspend fun hasCustomBootImage(): Result<Boolean> {
        return sshManager.executeCommand(
            "[ -f '$SPINNER_BACKUP_PATH' ] && echo 'true' || echo 'false'"
        ).mapCatching { it.trim() == "true" }
    }

    /**
     * 上传自定义启动图片到 C3，自动备份原图
     */
    suspend fun uploadBootImage(context: Context, imageUri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                // 从 Uri 读取图片到临时文件
                val tempFile = File(context.cacheDir, "boot_image_upload.png")
                context.contentResolver.openInputStream(imageUri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext Result.failure(Exception("无法读取选择的图片文件"))

                val fileSize = tempFile.length()
                if (fileSize > 2 * 1024 * 1024) {
                    tempFile.delete()
                    return@withContext Result.failure(Exception("图片文件过大（最大2MB），请压缩后再上传"))
                }

                // 检查是否已备份，未备份则先备份原图
                val backupExists = sshManager.executeCommand(
                    "[ -f '$SPINNER_BACKUP_PATH' ] && echo 'true' || echo 'false'"
                ).getOrElse { _ -> "false" }

                if (backupExists.trim() != "true") {
                    sshManager.executeCommand("cp '$SPINNER_IMAGE_PATH' '$SPINNER_BACKUP_PATH'")
                        .getOrThrow()
                }

                // 上传新图片
                sshManager.uploadFile(tempFile.absolutePath, SPINNER_IMAGE_PATH).getOrThrow()

                // 清理临时文件
                tempFile.delete()

                Result.success("启动图片已更新，重启 openpilot 后生效")
            } catch (e: Exception) {
                Log.e(TAG, "uploadBootImage failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 恢复原始启动图片
     */
    suspend fun restoreBootImage(): Result<String> {
        return sshManager.executeCommand(
            "if [ -f '$SPINNER_BACKUP_PATH' ]; then cp '$SPINNER_BACKUP_PATH' '$SPINNER_IMAGE_PATH' && echo 'OK'; else echo 'NO_BACKUP'; fi"
        ).mapCatching { output ->
            when {
                output.trim() == "OK" -> "已恢复原始启动图片，重启 openpilot 后生效"
                output.trim() == "NO_BACKUP" -> "未找到原始备份，无法恢复"
                else -> "操作结果：$output"
            }
        }
    }

    /**
     * 上传自定义声音文件到 C3，自动备份原文件
     */
    suspend fun uploadSoundFile(context: Context, soundDef: SoundDef, audioUri: Uri): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val remotePath = "$SOUNDS_DIR/${soundDef.fileName}"
                val backupPath = "$SOUNDS_DIR/${soundDef.fileName}.bak"

                // 从 Uri 读取到临时文件
                val tempFile = File(context.cacheDir, "sound_upload_${soundDef.key}.wav")
                context.contentResolver.openInputStream(audioUri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return@withContext Result.failure(Exception("无法读取选择的声音文件"))

                val fileSize = tempFile.length()
                if (fileSize > 5 * 1024 * 1024) {
                    tempFile.delete()
                    return@withContext Result.failure(Exception("声音文件过大（最大5MB）"))
                }

                // 备份原文件
                val backupExists = sshManager.executeCommand(
                    "[ -f '$backupPath' ] && echo 'true' || echo 'false'"
                ).getOrElse { _ -> "false" }

                if (backupExists.trim() != "true") {
                    sshManager.executeCommand("cp '$remotePath' '$backupPath'")
                        .getOrThrow()
                }

                // 上传新文件
                sshManager.uploadFile(tempFile.absolutePath, remotePath).getOrThrow()

                tempFile.delete()

                Result.success("${soundDef.name}声音已更新，重启 openpilot 后生效")
            } catch (e: Exception) {
                Log.e(TAG, "uploadSoundFile failed", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 恢复原始声音文件
     */
    suspend fun restoreSound(soundDef: SoundDef): Result<String> {
        val remotePath = "$SOUNDS_DIR/${soundDef.fileName}"
        val backupPath = "$SOUNDS_DIR/${soundDef.fileName}.bak"
        return sshManager.executeCommand(
            "if [ -f '$backupPath' ]; then cp '$backupPath' '$remotePath' && echo 'OK'; else echo 'NO_BACKUP'; fi"
        ).mapCatching { output ->
            when {
                output.trim() == "OK" -> "已恢复原始${soundDef.name}声音"
                output.trim() == "NO_BACKUP" -> "未找到${soundDef.name}的原始备份"
                else -> "操作结果：$output"
            }
        }
    }

    /**
     * 获取所有声音文件的自定义状态
     */
    suspend fun getCustomSoundStatus(): Result<Map<String, Boolean>> {
        val checks = SOUND_FILES.joinToString("; ") { def ->
            "echo '${def.key}:'$( [ -f '$SOUNDS_DIR/${def.fileName}.bak' ] && echo 'true' || echo 'false' )"
        }
        return sshManager.executeCommand(checks).mapCatching { output ->
            val result = mutableMapOf<String, Boolean>()
            output.lines().forEach { line ->
                val parts = line.trim().split(":")
                if (parts.size == 2) {
                    result[parts[0]] = parts[1] == "true"
                }
            }
            result
        }
    }

    /**
     * 读取 C3 音量值 (0.0 ~ 1.0)
     */
    suspend fun getVolume(): Result<Float> {
        return sshManager.executeCommand(
            "cat '$VOLUME_PARAM_PATH' 2>/dev/null || echo '1.0'"
        ).mapCatching { output ->
            try {
                output.trim().toFloat().coerceIn(0f, 1f)
            } catch (_: Exception) {
                1.0f
            }
        }
    }

    /**
     * 设置 C3 音量值 (0.0 ~ 1.0)
     */
    suspend fun setVolume(volume: Float): Result<String> {
        val clamped = volume.coerceIn(0f, 1f)
        return sshManager.executeCommand(
            "echo '$clamped' > '$VOLUME_PARAM_PATH' && echo 'OK'"
        ).mapCatching { output ->
            if (output.trim() == "OK") "音量已更新为 ${(clamped * 100).toInt()}%"
            else "设置结果: $output"
        }
    }

    /**
     * 下载 C3 上的启动图片到本地缓存
     */
    suspend fun downloadBootImagePreview(context: Context): Result<File> {
        return withContext(Dispatchers.IO) {
            val previewFile = File(context.cacheDir, "boot_preview.png")
            sshManager.downloadFile(SPINNER_IMAGE_PATH, previewFile)
                .map { previewFile }
                .getOrThrow()
        }
    }
}
