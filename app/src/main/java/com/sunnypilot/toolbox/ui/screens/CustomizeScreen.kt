package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.launch

// C3 风格配色 (与 SettingsScreen 一致)
private val C3Bg = Color(0xFF1A1A2E)
private val C3Card = Color(0xFF232340)
private val C3CardAlt = Color(0xFF2A2A4A)
private val C3Accent = Color(0xFF0D9488)
private val C3Text = Color(0xFFE2E8F0)
private val C3SubText = Color(0xFF94A3B8)
private val C3Divider = Color(0xFF334155)
private val C3Section = Color(0xFF1E293B)
private val C3Green = Color(0xFF34D399)
private val C3Red = Color(0xFFEF4444)
private val C3Yellow = Color(0xFFFBBF24)
private val C3Purple = Color(0xFFA78BFA)
private val C3Blue = Color(0xFF60A5FA)

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
    var showVolumeApplied by remember { mutableStateOf(false) }
    var customSoundStatus by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var hasCustomBootImage by remember { mutableStateOf<Boolean?>(null) }

    // 折叠面板
    val expandedPanels = remember { mutableStateMapOf("boot" to true, "sounds" to false, "volume" to false) }

    fun showToast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    // 加载初始数据
    LaunchedEffect(Unit) {
        repository.getVolume().onSuccess { volumeValue = it }
        repository.getCustomSoundStatus().onSuccess { customSoundStatus = it }
        repository.hasCustomBootImage().onSuccess { hasCustomBootImage = it }
    }

    // 文件选择器
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

    fun soundPickerLauncher(soundDef: CustomizeRepository.SoundDef) = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isLoading = true
                statusMessage = "正在上传${soundDef.name}声音..."
                showStatus = true
                repository.uploadSoundFile(context, soundDef, uri).fold(
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

    Box(modifier = modifier.fillMaxSize().background(C3Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // ========== 顶部渐变标题栏 ==========
            Surface(color = Color.Transparent) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    C3Accent.copy(alpha = 0.3f),
                                    C3Purple.copy(alpha = 0.15f),
                                    C3Section
                                )
                            )
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Palette,
                                contentDescription = null,
                                tint = C3Accent,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "个性化设置",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = C3Text
                            )
                            Spacer(Modifier.weight(1f))
                            if (isLoading) {
                                CircularProgressIndicator(
                                    color = C3Accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Text(
                            "自定义 C3 启动画面、提示声音和音量",
                            fontSize = 12.sp,
                            color = C3SubText,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // 状态提示条
            AnimatedVisibility(visible = showStatus) {
                Surface(
                    color = if (statusMessage.contains("失败")) C3Red.copy(alpha = 0.15f)
                    else C3Green.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (statusMessage.contains("失败")) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (statusMessage.contains("失败")) C3Red else C3Green,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            statusMessage,
                            fontSize = 12.sp,
                            color = C3Text,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { showStatus = false },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = C3SubText,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ========== 面板 1: 启动画面 ==========
            ExpandableSection(
                title = "启动画面",
                icon = Icons.Outlined.Image,
                isExpanded = expandedPanels["boot"] == true,
                onToggle = { expandedPanels["boot"] = !(expandedPanels["boot"] == true) },
                accentColor = C3Blue
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    // 当前状态
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(C3Card, RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Icon(
                            if (hasCustomBootImage == true) Icons.Default.Brush else Icons.Default.Image,
                            contentDescription = null,
                            tint = if (hasCustomBootImage == true) C3Green else C3SubText,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "当前状态",
                                fontSize = 12.sp,
                                color = C3SubText
                            )
                            Text(
                                when (hasCustomBootImage) {
                                    true -> "使用自定义启动图片"
                                    false -> "使用原始启动图片"
                                    null -> "检测中..."
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (hasCustomBootImage == true) C3Green else C3Text
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 预览说明
                    Surface(
                        color = C3CardAlt,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.PhoneAndroid,
                                contentDescription = null,
                                tint = C3Accent.copy(alpha = 0.6f),
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "360×360 PNG 图片",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = C3Text
                            )
                            Text(
                                "上传一张新的启动图片，将替换 C3 开机时显示的 sunnypilot LOGO。支持 PNG 格式，建议分辨率 360×360。",
                                fontSize = 11.sp,
                                color = C3SubText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 操作按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 上传按钮
                        Button(
                            onClick = { imagePickerLauncher.launch("image/png") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = C3Blue),
                            shape = RoundedCornerShape(10.dp),
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("上传新图片", fontSize = 12.sp)
                        }

                        // 恢复按钮
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    repository.restoreBootImage().fold(
                                        onSuccess = { msg ->
                                            statusMessage = msg
                                            showStatus = true
                                            hasCustomBootImage = false
                                            showToast(msg)
                                        },
                                        onFailure = { e ->
                                            showToast("恢复失败：${e.message}")
                                        }
                                    )
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = C3Red),
                            enabled = !isLoading && hasCustomBootImage == true
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("恢复原始", fontSize = 12.sp)
                        }
                    }

                    Text(
                        "更换后需重启 openpilot 才能看到效果",
                        fontSize = 10.sp,
                        color = C3SubText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // ========== 面板 2: 声音管理 ==========
            ExpandableSection(
                title = "声音管理",
                icon = Icons.Outlined.MusicNote,
                isExpanded = expandedPanels["sounds"] == true,
                onToggle = { expandedPanels["sounds"] = !(expandedPanels["sounds"] == true) },
                accentColor = C3Purple
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "可以为每个提示音单独上传自定义音频文件（48kHz 单声道 WAV）",
                        fontSize = 11.sp,
                        color = C3SubText,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )

                    CustomizeRepository.SOUND_FILES.forEach { soundDef ->
                        val isCustomized = customSoundStatus[soundDef.key] == true
                        SoundItemCard(
                            soundDef = soundDef,
                            isCustomized = isCustomized,
                            isLoading = isLoading,
                            onUpload = { soundPickerLauncher(soundDef).launch("audio/*") },
                            onRestore = {
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
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // ========== 面板 3: 音量控制 ==========
            ExpandableSection(
                title = "音量控制",
                icon = Icons.Outlined.VolumeUp,
                isExpanded = expandedPanels["volume"] == true,
                onToggle = { expandedPanels["volume"] = !(expandedPanels["volume"] == true) },
                accentColor = C3Yellow
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Surface(
                        color = C3CardAlt,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = C3Yellow,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "主音量",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = C3Text
                                )
                                Spacer(Modifier.weight(1f))
                                // 音量百分比
                                Text(
                                    "${(volumeValue * 100).toInt()}%",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = C3Yellow
                                )
                            }

                            Spacer(Modifier.height(12.dp))

                            // 音量滑块
                            Slider(
                                value = volumeValue,
                                onValueChange = { volumeValue = it },
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth(),
                                colors = SliderDefaults.colors(
                                    thumbColor = C3Yellow,
                                    activeTrackColor = C3Yellow,
                                    inactiveTrackColor = C3Divider
                                )
                            )

                            Spacer(Modifier.height(8.dp))

                            // 滑块标签
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("0%", fontSize = 10.sp, color = C3SubText)
                                Text("25%", fontSize = 10.sp, color = C3SubText)
                                Text("50%", fontSize = 10.sp, color = C3SubText)
                                Text("75%", fontSize = 10.sp, color = C3SubText)
                                Text("100%", fontSize = 10.sp, color = C3SubText)
                            }

                            Spacer(Modifier.height(16.dp))

                            // 应用按钮
                            Button(
                                onClick = {
                                    scope.launch {
                                        isLoading = true
                                        repository.setVolume(volumeValue).fold(
                                            onSuccess = { msg ->
                                                showVolumeApplied = true
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
                                colors = ButtonDefaults.buttonColors(containerColor = C3Accent),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !isLoading
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("应用音量", fontSize = 13.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 提示信息
                    Surface(
                        color = C3Accent.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = C3Accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "音量调节为主音量控制，0%为静音，100%为最大音量。C3 会根据环境噪音自动微调，此设置为上限值。",
                                fontSize = 11.sp,
                                color = C3SubText,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 可折叠面板组件 ====================

@Composable
private fun ExpandableSection(
    title: String,
    icon: ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = C3Section,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.animateContentSize()
        ) {
            // 标题栏 - 可点击折叠
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                // 左侧彩色指示条
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor)
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = C3Text,
                    modifier = Modifier.weight(1f)
                )
                // 折叠箭头
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = C3SubText,
                    modifier = Modifier.size(22.dp)
                )
            }

            // 内容区域
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                    content = content
                )
            }
        }
    }
}

// ==================== 声音项目卡片 ====================

@Composable
private fun SoundItemCard(
    soundDef: CustomizeRepository.SoundDef,
    isCustomized: Boolean,
    isLoading: Boolean,
    onUpload: () -> Unit,
    onRestore: () -> Unit
) {
    Surface(
        color = C3Card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 声音图标
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCustomized) C3Green.copy(alpha = 0.15f) else C3Purple.copy(alpha = 0.1f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = if (isCustomized) C3Green else C3Purple,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            soundDef.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = C3Text
                        )
                        if (isCustomized) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = C3Green.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "已自定义",
                                    fontSize = 9.sp,
                                    color = C3Green,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        soundDef.description,
                        fontSize = 11.sp,
                        color = C3SubText,
                        lineHeight = 14.sp
                    )
                    Text(
                        soundDef.fileName,
                        fontSize = 10.sp,
                        color = C3SubText.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 恢复按钮（仅自定义时可见）
                if (isCustomized) {
                    TextButton(
                        onClick = onRestore,
                        enabled = !isLoading,
                        colors = ButtonDefaults.textButtonColors(contentColor = C3Red)
                    ) {
                        Icon(
                            Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("恢复原始", fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }

                // 上传按钮
                Button(
                    onClick = onUpload,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = C3Purple.copy(alpha = 0.8f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    enabled = !isLoading,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        Icons.Default.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isCustomized) "更换声音" else "上传声音",
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
