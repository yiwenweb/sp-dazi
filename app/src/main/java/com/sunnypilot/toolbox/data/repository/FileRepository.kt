package com.sunnypilot.toolbox.data.repository

import com.jcraft.jsch.ChannelSftp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.FileEntry
import com.sunnypilot.toolbox.model.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * C3 文件系统操作仓库 — 基于 SFTP 协议。
 *
 * SFTP 比 shell 命令（stat/find/head/rm）更快、更可靠，
 * 且原生支持上传/下载/重命名/创建目录等功能。
 * 仅搜索依赖 shell find（SFTP 无原生搜索能力）。
 */
class FileRepository(private val sshManager: SshManager) {

    /** SFTP 逐项列出目录内容，替代 shell stat */
    suspend fun listFiles(path: String): Result<List<FileEntry>> {
        return sshManager.withSftp { channel ->
            @Suppress("UNCHECKED_CAST")
            val raw = channel.ls(path) as Vector<ChannelSftp.LsEntry>
            val parent = path.trimEnd('/')
            raw.asSequence()
                .filter { it.filename !in setOf(".", "..") }
                .map { it.toFileEntry(parent) }
                .sortedWith(
                    compareByDescending<FileEntry> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                )
                .toList()
        }
    }

    /** shell find 搜索（SFTP 无此能力，保留 shell） */
    suspend fun searchFiles(query: String, basePath: String = "/data"): Result<List<FileEntry>> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext Result.success(emptyList())
            val sq = query.replace("'", "'\\''")
            val base = basePath.replace("'", "'\\''")
            val script = """
            base='${base}'
            sq='${sq}'
            find "${'$'}base" -maxdepth 4 -iname "*${'$'}sq*" -not -path '*/\.*' 2>/dev/null |
            head -200 | while read f; do
              stat -c '%F|%s|%Y|%A|%n' "${'$'}f" 2>/dev/null
            done
        """.trimIndent()
            sshManager.executeCommand(script).map { raw ->
                raw.lineSequence().filter { it.contains("|") }.mapNotNull { line ->
                    val p = line.split("|")
                    if (p.size < 5) return@mapNotNull null
                    val type = p[0]; val size = p[1].toLongOrNull() ?: 0L
                    val epoch = p[2].toLongOrNull() ?: 0L; val perms = p[3]
                    val fullPath = p.drop(4).joinToString("|")
                    val name = fullPath.substringAfterLast("/")
                    val dateStr = if (epoch > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(Date(epoch * 1000)) else ""
                    FileEntry(name, fullPath, type.contains("directory"), size,
                        formatFileSize(size), dateStr, perms, type.contains("symbolic link"))
                }
                .sortedWith(compareByDescending<FileEntry> { it.isDirectory }
                    .thenBy { it.name.lowercase() })
                .toList()
            }
        }

    /** SFTP 读取文件预览（前 maxLines 行） */
    suspend fun getFilePreview(path: String, maxLines: Int = 200): Result<String> =
        withContext(Dispatchers.IO) {
            sshManager.readFile(path).map { content ->
                content.lineSequence().take(maxLines).joinToString("\n")
            }
        }

    /** SFTP 读取完整文件内容（用于编辑） */
    suspend fun readFileContent(path: String): Result<String> = withContext(Dispatchers.IO) {
        sshManager.readFile(path)
    }

    /** SFTP 删除文件（递归删除目录） */
    suspend fun deleteFile(path: String, isDir: Boolean): Result<Unit> =
        sshManager.deleteRemote(path, isDir)

    /** SFTP 下载到本地 */
    suspend fun downloadFile(remotePath: String, localPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            sshManager.downloadFile(remotePath, java.io.File(localPath))
        }

    /** SFTP 上传本地文件到 C3 */
    suspend fun uploadFile(localPath: String, remotePath: String): Result<Unit> =
        sshManager.uploadFile(localPath, remotePath)

    /** SFTP 保存文本内容到远程文件（覆盖写） */
    suspend fun saveFile(remotePath: String, content: String): Result<Unit> =
        sshManager.writeTextFile(remotePath, content)

    /** SFTP 重命名/移动 */
    suspend fun renameFile(oldPath: String, newPath: String): Result<Unit> =
        sshManager.renameRemote(oldPath, newPath)

    /** SFTP 创建目录 */
    suspend fun createDirectory(path: String): Result<Unit> =
        sshManager.createDirectory(path)

    // ── helpers ──

    private fun ChannelSftp.LsEntry.toFileEntry(parentPath: String): FileEntry {
        val attrs = this.attrs
        val fullPath = if (parentPath == "/") "/$filename" else "$parentPath/$filename"
        return FileEntry(
            name = filename,
            path = fullPath,
            isDirectory = attrs.isDir,
            size = attrs.size,
            sizeHuman = formatFileSize(attrs.size),
            lastModified = if (attrs.mTime > 0)
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    .format(Date(attrs.mTime * 1000L)) else "",
            permissions = formatSftpPermissions(attrs.permissions),
            isSymlink = attrs.isLink
        )
    }

    companion object {
        /** 将 SFTP 整数权限转成 rwxr-xr-x 字符串 */
        fun formatSftpPermissions(perms: Int): String = buildString {
            append(if (perms and 0x400 != 0) 'r' else '-')
            append(if (perms and 0x200 != 0) 'w' else '-')
            append(if (perms and 0x100 != 0) 'x' else '-')
            append(if (perms and 0x040 != 0) 'r' else '-')
            append(if (perms and 0x020 != 0) 'w' else '-')
            append(if (perms and 0x010 != 0) 'x' else '-')
            append(if (perms and 0x004 != 0) 'r' else '-')
            append(if (perms and 0x002 != 0) 'w' else '-')
            append(if (perms and 0x001 != 0) 'x' else '-')
        }
    }
}
