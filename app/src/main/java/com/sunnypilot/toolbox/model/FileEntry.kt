package com.sunnypilot.toolbox.model

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val sizeHuman: String = "",
    val lastModified: String = "",
    val permissions: String = "",
    val isSymlink: Boolean = false,
    val symlinkTarget: String = ""
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val icon: String get() = when {
        isDirectory -> "folder"
        extension in IMAGE_EXT -> "image"
        extension in VIDEO_EXT -> "video"
        extension in AUDIO_EXT -> "audio"
        extension in ARCHIVE_EXT -> "archive"
        extension in CODE_EXT -> "code"
        extension in DOC_EXT -> "doc"
        extension in CONFIG_EXT -> "config"
        else -> "file"
    }

    /** 是否为文本类文件（可在 App 内编辑） */
    val isEditable: Boolean get() = !isDirectory && extension in EDITABLE_EXT && size < MAX_EDIT_BYTES
    val isTextFile: Boolean get() = !isDirectory && extension in EDITABLE_EXT

    companion object {
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")
        val VIDEO_EXT = setOf("mp4", "avi", "mkv", "mov", "wmv", "flv", "webm")
        val AUDIO_EXT = setOf("mp3", "wav", "flac", "aac", "ogg", "wma")
        val ARCHIVE_EXT = setOf("zip", "tar", "gz", "bz2", "xz", "7z", "rar")
        val CODE_EXT = setOf("py", "js", "ts", "kt", "java", "c", "cpp", "cc", "h", "hpp",
            "go", "rs", "swift", "sh", "bash", "pl", "rb", "lua", "php", "sql")
        val DOC_EXT = setOf("txt", "md", "log", "csv", "json", "xml", "yaml", "yml", "toml",
            "html", "css", "scss", "less", "gradle", "kts", "prop", "cmake")
        val CONFIG_EXT = setOf("cfg", "conf", "ini", "env", "properties")
        /** 支持在 App 内编辑的文件扩展名合并集合 */
        val EDITABLE_EXT = CODE_EXT + DOC_EXT + CONFIG_EXT
        /** 编辑上限 200KB，超过则只允许预览 */
        const val MAX_EDIT_BYTES = 200_000L
    }
}

fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
