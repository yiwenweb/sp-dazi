package com.sunnypilot.toolbox.data.repository

import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.FileEntry
import com.sunnypilot.toolbox.model.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * C3 文件系统操作仓库。
 * 所有操作通过 SSH 在远程设备上执行。
 */
class FileRepository(private val sshManager: SshManager) {

    /**
     * 通过单条 SSH 命令批量 `stat` 列出目录内容。
     * 输出格式: %F|%s|%Y|%A|%n
     *   directory|4096|1736959823|drwxr-xr-x|dirname
     */
    suspend fun listFiles(path: String): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        val safe = shellEscape(path)
        val script = """
            base='${safe}'
            for f in "${'$'}base"/*; do
              [ -e "${'$'}f" ] || continue
              stat -c '%F|%s|%Y|%A|%n' "${'$'}f" 2>/dev/null
            done
            for f in "${'$'}base"/.*; do
              nm=${'$'}(basename "${'$'}f")
              [ "${'$'}nm" = "." ] && continue
              [ "${'$'}nm" = ".." ] && continue
              [ -e "${'$'}f" ] || continue
              stat -c '%F|%s|%Y|%A|%n' "${'$'}f" 2>/dev/null
            done
        """.trimIndent()

        sshManager.executeCommand(script).map { raw ->
            raw.lineSequence()
                .filter { it.contains("|") }
                .mapNotNull { line ->
                    val p = line.split("|")
                    if (p.size < 5) return@mapNotNull null
                    val type = p[0]
                    val size = p[1].toLongOrNull() ?: 0L
                    val epoch = p[2].toLongOrNull() ?: 0L
                    val perms = p[3]
                    val fullPath = p.drop(4).joinToString("|")  // 路径可能含 | 符号

                    val name = fullPath.substringAfterLast("/")
                    val dateStr = if (epoch > 0) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(Date(epoch * 1000))
                    } else ""

                    FileEntry(
                        name = name,
                        path = fullPath,
                        isDirectory = type.contains("directory"),
                        size = size,
                        sizeHuman = formatFileSize(size),
                        lastModified = dateStr,
                        permissions = perms,
                        isSymlink = type.contains("symbolic link")
                    )
                }
                .filter { it.name !in setOf(".", "..") }
                .sortedWith(compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() })
                .toList()
        }
    }

    /**
     * 递归搜索文件（最多 4 层）。
     */
    suspend fun searchFiles(query: String, basePath: String = "/data"): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())
        val sq = shellEscape(query)
        val base = shellEscape(basePath)
        val script = """
            base='${base}'
            sq='${sq}'
            find "${'$'}base" -maxdepth 4 -iname "*${'$'}sq*" -not -path '*/\.*' 2>/dev/null |
            head -200 | while read f; do
              stat -c '%F|%s|%Y|%A|%n' "${'$'}f" 2>/dev/null
            done
        """.trimIndent()
        sshManager.executeCommand(script).map { raw ->
            raw.lineSequence()
                .filter { it.contains("|") }
                .mapNotNull { line ->
                    val p = line.split("|")
                    if (p.size < 5) return@mapNotNull null
                    val type = p[0]
                    val size = p[1].toLongOrNull() ?: 0L
                    val epoch = p[2].toLongOrNull() ?: 0L
                    val perms = p[3]
                    val fullPath = p.drop(4).joinToString("|")
                    val name = fullPath.substringAfterLast("/")
                    val dateStr = if (epoch > 0) {
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(Date(epoch * 1000))
                    } else ""
                    FileEntry(name, fullPath, type.contains("directory"), size,
                        formatFileSize(size), dateStr, perms, type.contains("symbolic link"))
                }
                .sortedWith(compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() })
                .toList()
        }
    }

    /**
     * 读取文本文件预览（前 N 行）。
     */
    suspend fun getFilePreview(path: String, maxLines: Int = 200): Result<String> = withContext(Dispatchers.IO) {
        sshManager.executeCommand("head -$maxLines '${shellEscape(path)}' 2>&1")
    }

    /**
     * 删除文件或目录。
     */
    suspend fun deleteFile(path: String, isDir: Boolean): Result<String> = withContext(Dispatchers.IO) {
        val flag = if (isDir) "-rf" else "-f"
        sshManager.executeCommand("rm $flag '${shellEscape(path)}' 2>&1")
    }

    /**
     * 获取文件/目录总大小（du -sh）。
     */
    suspend fun getSummarySize(path: String): Result<String> = withContext(Dispatchers.IO) {
        sshManager.executeCommand("du -sh '${shellEscape(path)}' 2>/dev/null | awk '{print \$1}'")
    }

    private companion object {
        fun shellEscape(s: String) = s.replace("'", "'\\''")
    }
}
