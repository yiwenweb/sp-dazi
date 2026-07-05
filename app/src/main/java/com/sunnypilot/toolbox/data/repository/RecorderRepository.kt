package com.sunnypilot.toolbox.data.repository

import android.content.Context
import android.util.Log
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.RecorderOverlay
import java.io.File

class RecorderRepository(
    private val context: Context,
    private val sshManager: SshManager
) {
    companion object {
        const val DEFAULT_REALDATA_PATH = "/data/media/0/realdata"
        const val DEFAULT_VIDEOS_PATH = "/data/media/0/videos"
        private const val TAG = "RecorderRepository"
    }

    private fun cacheDir(): File = File(context.cacheDir, "recorder").apply { mkdirs() }

    private fun localOverlay(segmentId: String): File = File(cacheDir(), "$segmentId/overlay.json")
    private fun localVideo(segmentId: String): File = File(cacheDir(), "$segmentId/qcamera.ts")

    suspend fun listSegments(remotePath: String = DEFAULT_REALDATA_PATH): Result<List<SegmentSummary>> {
        return sshManager.executeCommand(
            "for d in $remotePath/*--*; do " +
            "[ -d \"\$d\" ] || continue; " +
            "seg=\"\$(basename \"\$d\")\"; " +
            "has_qlog=0; has_video=0; has_overlay=0; " +
            "[ -f \"\$d/qlog.zst\" ] || [ -f \"\$d/qlog.bz2\" ] || [ -f \"\$d/qlog\" ] && has_qlog=1; " +
            "[ -f \"\$d/qcamera.ts\" ] && has_video=1; " +
            "[ -f \"\$d/overlay.json\" ] && has_overlay=1; " +
            "echo \"\$seg|\$has_qlog|\$has_video|\$has_overlay\"; done"
        ).map { output ->
            output.lines().mapNotNull { line ->
                val parts = line.trim().split("|")
                if (parts.size == 4) {
                    val segmentId = parts[0]
                    SegmentSummary(
                        segmentId = segmentId,
                        hasQlog = parts[1] == "1",
                        hasVideo = parts[2] == "1",
                        hasOverlay = parts[3] == "1",
                        cachedOverlay = localOverlay(segmentId).exists(),
                        cachedVideo = localVideo(segmentId).exists()
                    )
                } else null
            }
        }
    }

    suspend fun ensureOverlay(segmentId: String, remotePath: String = DEFAULT_REALDATA_PATH): Result<RecorderOverlay> {
        val local = localOverlay(segmentId)
        if (local.exists()) {
            return try {
                Result.success(RecorderOverlay.fromJson(local.readText()))
            } catch (e: Exception) {
                Log.w(TAG, "本地 overlay 解析失败，重新下载", e)
                local.delete()
                downloadOverlay(segmentId, remotePath)
            }
        }
        return downloadOverlay(segmentId, remotePath)
    }

    private suspend fun downloadOverlay(segmentId: String, remotePath: String): Result<RecorderOverlay> {
        val remote = "$remotePath/$segmentId/overlay.json"
        val local = localOverlay(segmentId)
        return sshManager.readFile(remote).mapCatching { text ->
            local.parentFile?.mkdirs()
            local.writeText(text)
            RecorderOverlay.fromJson(text)
        }
    }

    suspend fun ensureVideo(segmentId: String, remotePath: String = DEFAULT_REALDATA_PATH): Result<File> {
        val local = localVideo(segmentId)
        if (local.exists() && local.length() > 0) {
            return Result.success(local)
        }
        val remote = "$remotePath/$segmentId/qcamera.ts"
        return sshManager.downloadFile(remote, local).map { local }
    }

    suspend fun preprocessSegment(segmentId: String, remotePath: String = DEFAULT_REALDATA_PATH): Result<Unit> {
        return sshManager.executeCommand(
            "cd /data/openpilot && python3 /data/openpilot/c3_scripts/preprocess_recorder.py $remotePath/$segmentId"
        ).map { }
    }

    fun localVideoFile(segmentId: String): File = localVideo(segmentId)

    fun hasLocalCache(segmentId: String): Boolean =
        localOverlay(segmentId).exists() || localVideo(segmentId).exists()

    fun deleteLocalCache(segmentId: String): Result<Unit> = try {
        localOverlay(segmentId).deleteRecursively()
        localVideo(segmentId).delete()
        // 如果 segment 目录已空，也一并删除
        File(cacheDir(), segmentId).takeIf { it.exists() && (it.list()?.isEmpty() ?: false) }?.delete()
        Result.success(Unit)
    } catch (e: Exception) {
        Log.e(TAG, "删除本地缓存失败", e)
        Result.failure(e)
    }
}

data class SegmentSummary(
    val segmentId: String,
    val hasQlog: Boolean,
    val hasVideo: Boolean,
    val hasOverlay: Boolean,
    val cachedOverlay: Boolean = false,
    val cachedVideo: Boolean = false
)
