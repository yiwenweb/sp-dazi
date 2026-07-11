package com.sunnypilot.toolbox.ui.screens

import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.CustomizeRepository
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun CustomizeScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { CustomizeRepository(sshManager) }

    // 状态
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var showStatus by remember { mutableStateOf(false) }
    var volumeValue by remember { mutableFloatStateOf(1.0f) }
    var customSoundStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var hasCustomBootImage by remember { mutableStateOf<Boolean?>(null) }
    var bootImageExists by remember { mutableStateOf<Boolean?>(null) }
    var bootPreviewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var bootPreviewLoading by remember { mutableStateOf(false) }
    var playingSoundKey by remember { mutableStateOf<String?>(null) }

    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    fun loadBootPreview() {
        scope.launch {
            bootPreviewLoading = true
            repository.downloadBootImagePreview(context).fold(
                onSuccess = { file ->
                    bootPreviewBitmap = try {
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    } catch (_: Exception) { null }
                },
                onFailure = { bootPreviewBitmap = null }
            )
            bootPreviewLoading = false
        }
    }

    fun checkBootImageExists() {
        scope.launch {
            repository.hasCustomBootImage().onSuccess { hasCustomBootImage = it }
            val exists = sshManager.executeCommand(
                "[ -f '${CustomizeRepository.SPINNER_IMAGE_PATH}' ] && echo 'true' || echo 'false'"
            ).getOrNull()?.trim() == "true"
            bootImageExists = exists
            if (exists) loadBootPreview()
        }
    }

    fun playSoundPreview(soundDef: CustomizeRepository.SoundDef) {
        scope.launch {
            isLoading = true
            val remotePath = "${CustomizeRepository.SOUNDS_DIR}/${soundDef.fileName}"
            val tempFile = File(context.cacheDir, "preview_${soundDef.fileName}")
            sshManager.downloadFile(remotePath, tempFile).fold(
                onSuccess = {
                    try {
                        mediaPlayer?.release()
                        val mp = MediaPlayer()
                        mp.setDataSource(tempFile.absolutePath)
                        mp.prepare()
                        mp.setOnCompletionListener {
                            playingSoundKey = null
                        }
                        mp.start()
                        mediaPlayer = mp
                        playingSoundKey = soundDef.key
                    } catch (e: Exception) {
                        showToast("试听失败：${e.message}")
                    }
                },
                onFailure = { e ->
                    showToast("下载声音失败：${e.message}")
                }
            )
            isLoading = false
        }
    }

    fun stopSoundPreview() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
            mediaPlayer = null
        }
        playingSoundKey = null
    }

    LaunchedEffect(Unit) {
        repository.getVolume().onSuccess { volumeValue = it }
        repository.getCustomSoundStatus().onSuccess { customSoundStatus = it }
        checkBootImageExists()
    }

    // 图片选择器
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                statusMessage = "正在上传启动图片..."
                showStatus = true
                repository.uploadBootImage(context, uri).fold(
                    onSuccess = { msg ->
                        statusMessage = msg
                        hasCustomBootImage = true
                        checkBootImageExists()
                        showToast(msg)
                    },
                    onFailure = { e ->
                        statusMessage = "上传失败：${e.message}"
                        showToast(statusMessage)
                    }
                )
                isLoading = false
            }
        }
    }

    // 声音选择器
    var pendingSoundDef by remember { mutableStateOf<CustomizeRepository.SoundDef?>(null) }
    val audioPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val def = pendingSoundDef ?: return@rememberLauncherForActivityResult
        pendingSoundDef = null
        if (uri != null) {
            scope.launch {
                isLoading = true
                statusMessage = "正在上传${def.name}声音..."
                showStatus = true
                repository.uploadSoundFile(context, def, uri).fold(
                    onSuccess = { msg ->
                        statusMessage = msg
                        showToast(msg)
                        repository.getCustomSoundStatus().onSuccess { customSoundStatus = it }
                    },
                    onFailure = { e ->
                        statusMessage = "上传失败：${e.message}"
                        showToast(statusMessage)
                    }
                )
                isLoading = false
            }
        }
    }

    fun pickSoundFile(soundDef: CustomizeRepository.SoundDef) {
        pendingSoundDef = soundDef
        audioPickerLauncher.launch("audio/*")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // ========== 标题栏 ==========
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Teal50, Teal100.copy(alpha = 0.3f), Background)
                    )
                )
                .padding(28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Teal500.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Palette,
                        contentDescription = null,
                        tint = Teal500,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "个性化设置",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Slate900
                    )
                    Text(
                        "自定义 C3 启动画面、提示声音和音量",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate600
                    )
                }
                Spacer(Modifier.weight(1f))
                if (isLoading) {
                    CircularProgressIndicator(
                        color = Teal500,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // 状态提示条
        AnimatedVisibility(visible = showStatus) {
            Surface(
                color = if (statusMessage.contains("失败")) Red50 else Green50,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (statusMessage.contains("失败")) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (statusMessage.contains("失败")) Red500 else Green500,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        statusMessage,
                        fontSize = 14.sp,
                        color = Slate900,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showStatus = false },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // ========== 启动画面 ==========
        SectionCard(title = "启动画面", icon = Icons.Outlined.Image, accentColor = Teal500) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 当前状态 + 预览
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    // 左侧：状态信息
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        InfoRow(
                            label = "当前状态",
                            value = when {
                                bootImageExists == null -> "检测中..."
                                bootImageExists == false -> "C3 上未找到启动图片文件"
                                hasCustomBootImage == true -> "已自定义"
                                else -> "使用原始图片"
                            },
                            valueColor = when {
                                bootImageExists == false -> Red500
                                hasCustomBootImage == true -> Green500
                                else -> Teal500
                            }
                        )
                        InfoRow(
                            label = "文件路径",
                            value = CustomizeRepository.SPINNER_IMAGE_PATH
                        )
                        InfoRow(
                            label = "格式要求",
                            value = "PNG 图片，建议 360×360 像素"
                        )
                        InfoRow(
                            label = "生效方式",
                            value = "上传后重启 openpilot 生效"
                        )
                    }

                    // 右侧：预览图
                    Surface(
                        color = Panel,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.size(160.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            when {
                                bootPreviewLoading -> {
                                    CircularProgressIndicator(
                                        color = Teal500,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                                bootPreviewBitmap != null -> {
                                    Image(
                                        bitmap = bootPreviewBitmap!!.asImageBitmap(),
                                        contentDescription = "启动图片预览",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(12.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                bootImageExists == false -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.BrokenImage,
                                            contentDescription = null,
                                            tint = Slate400,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "文件不存在",
                                            fontSize = 11.sp,
                                            color = Slate400,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                                else -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Image,
                                            contentDescription = null,
                                            tint = Slate400,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Text(
                                            "暂无预览",
                                            fontSize = 11.sp,
                                            color = Slate400,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 操作按钮
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { imagePickerLauncher.launch("image/png") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("上传新图片", fontSize = 14.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                repository.restoreBootImage().fold(
                                    onSuccess = { msg ->
                                        statusMessage = msg
                                        showStatus = true
                                        hasCustomBootImage = false
                                        checkBootImageExists()
                                        showToast(msg)
                                    },
                                    onFailure = { e -> showToast("恢复失败：${e.message}") }
                                )
                                isLoading = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Red500),
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("恢复默认", fontSize = 14.sp)
                    }
                }
            }
        }

        // ========== 声音管理 ==========
        SectionCard(title = "声音管理", icon = Icons.Outlined.MusicNote, accentColor = Purple500) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "可以为每个提示音单独上传自定义音频文件（48kHz 单声道 WAV），支持试听和恢复默认",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )

                CustomizeRepository.SOUND_FILES.forEach { soundDef ->
                    val isCustomized = customSoundStatus[soundDef.key] == true
                    val isPlaying = playingSoundKey == soundDef.key

                    Surface(
                        color = Panel,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            // 图标
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isCustomized) Purple500.copy(alpha = 0.1f)
                                        else Blue500.copy(alpha = 0.1f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (isCustomized) Purple500 else Blue500,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(Modifier.width(14.dp))

                            // 信息
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        soundDef.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Slate900
                                    )
                                    if (isCustomized) {
                                        Spacer(Modifier.width(8.dp))
                                        Surface(
                                            color = Green50,
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                "已自定义",
                                                fontSize = 10.sp,
                                                color = Green500,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    soundDef.description,
                                    fontSize = 12.sp,
                                    color = Slate600,
                                    lineHeight = 16.sp
                                )
                                Text(
                                    soundDef.fileName,
                                    fontSize = 11.sp,
                                    color = Slate400
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            // 操作按钮
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // 试听按钮
                                IconButton(
                                    onClick = {
                                        if (isPlaying) stopSoundPreview()
                                        else playSoundPreview(soundDef)
                                    },
                                    modifier = Modifier.size(36.dp),
                                    enabled = !isLoading
                                ) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "停止" else "试听",
                                        tint = if (isPlaying) Red500 else Teal500,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // 恢复默认
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            isLoading = true
                                            repository.restoreSound(soundDef).fold(
                                                onSuccess = { msg ->
                                                    showToast(msg)
                                                    repository.getCustomSoundStatus().onSuccess { customSoundStatus = it }
                                                },
                                                onFailure = { e -> showToast("恢复失败：${e.message}") }
                                            )
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier.size(36.dp),
                                    enabled = !isLoading && isCustomized
                                ) {
                                    Icon(
                                        Icons.Default.Restore,
                                        contentDescription = "恢复默认",
                                        tint = if (isCustomized) Red500 else Slate400,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // 上传按钮
                                Button(
                                    onClick = { pickSoundFile(soundDef) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                                    shape = RoundedCornerShape(8.dp),
                                    enabled = !isLoading,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Upload,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (isCustomized) "更换" else "上传",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ========== 音量控制 ==========
        SectionCard(title = "音量控制", icon = Icons.Outlined.VolumeUp, accentColor = Amber500) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = Amber500,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "主音量",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate900
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(volumeValue * 100).toInt()}%",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Amber500
                    )
                }

                Slider(
                    value = volumeValue,
                    onValueChange = { volumeValue = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = Amber500,
                        activeTrackColor = Amber500,
                        inactiveTrackColor = Slate200
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0%", fontSize = 12.sp, color = Slate400)
                    Text("25%", fontSize = 12.sp, color = Slate400)
                    Text("50%", fontSize = 12.sp, color = Slate400)
                    Text("75%", fontSize = 12.sp, color = Slate400)
                    Text("100%", fontSize = 12.sp, color = Slate400)
                }

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            repository.setVolume(volumeValue).fold(
                                onSuccess = { msg ->
                                    showToast(msg)
                                },
                                onFailure = { e ->
                                    showToast("设置失败：${e.message}")
                                }
                            )
                            isLoading = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("应用音量", fontSize = 14.sp)
                }

                Surface(
                    color = Teal50,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = Teal500,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "音量调节为主音量控制，0%为静音，100%为最大音量。C3 会根据环境噪音自动微调，此设置为其上限值。",
                            fontSize = 12.sp,
                            color = Slate600,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }
    }
}

// ==================== 通用组件 ====================

@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Panel)
            .padding(24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = accentColor
            )
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = Slate900
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = Slate400,
            modifier = Modifier.width(72.dp)
        )
        Text(
            value,
            fontSize = 13.sp,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}
