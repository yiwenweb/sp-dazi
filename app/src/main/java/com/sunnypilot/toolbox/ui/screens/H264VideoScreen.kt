package com.sunnypilot.toolbox.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.H264VideoRepository
import com.sunnypilot.toolbox.data.repository.HudDataRepository
import com.sunnypilot.toolbox.data.repository.HudData
import com.sunnypilot.toolbox.ui.components.HudOverlay
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.*
import java.nio.ByteBuffer

/**
 * 摄像头实时流 — H264 硬件解码方案
 *
 * 原理：
 *   C3 端 h264_forward.py 转发 stream_encoderd 的 H264 帧（零 CPU 占用）
 *   Android 端通过 WebSocket 接收 H264 数据
 *   使用 MediaCodec 硬件解码，输出到 SurfaceView
 *
 * 优势：
 *   - C3 端零 CPU 占用（只转发，不解码）
 *   - Android 端硬件解码，流畅清晰
 *   - 可以显示原分辨率（1280x720）
 *   - 延迟极低
 *
 * 服务状态管理：
 *   - 进入页面自动检查服务状态
 *   - 如果服务已运行，直接连接（不杀进程）
 *   - 如果服务未运行，提示用户启动
 *   - 用户可通过设置菜单手动控制服务
 */
enum class H264CameraType(val key: String, val title: String, val desc: String) {
    ROAD("road", "主视角", "正前方，ACC/车道保持视线"),
    WIDE("wideRoad", "广角", "两侧变道/盲区视角"),
}

@Composable
fun H264VideoScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val h264Repo = remember { H264VideoRepository(context, sshManager) }
    val hudRepo = remember { HudDataRepository(context, sshManager) }
    val c3Ip = remember { sshManager.connectedHost }

    // 状态变量
    var camera by remember { mutableStateOf(H264CameraType.ROAD) }
    var error by remember { mutableStateOf<String?>(null) }
    var hudData by remember { mutableStateOf<HudData?>(null) }
    var showHud by remember { mutableStateOf(true) }
    var fps by remember { mutableIntStateOf(0) }
    var retryKey by remember { mutableIntStateOf(0) }
    
    // 服务状态
    var h264ServiceStatus by remember { mutableStateOf(ServiceStatus.UNKNOWN) }
    var hudServiceStatus by remember { mutableStateOf(ServiceStatus.UNKNOWN) }
    var isCheckingServices by remember { mutableStateOf(false) }
    
    // 视频状态
    var isStreamRunning by remember { mutableStateOf(false) }
    var lastFrameTime by remember { mutableLongStateOf(0L) }
    
    // MediaCodec 解码器（在后台线程创建）
    val decoder = remember { mutableStateOf<MediaCodec?>(null) }
    val surface = remember { mutableStateOf<Surface?>(null) }

    // 进入页面时检查两个服务的状态
    LaunchedEffect(Unit) {
        if (c3Ip.isNullOrBlank()) {
            h264ServiceStatus = ServiceStatus.ERROR
            hudServiceStatus = ServiceStatus.ERROR
            return@LaunchedEffect
        }
        
        isCheckingServices = true
        
        // 检查 H264 服务（带重试）
        for (attempt in 0 until 3) {
            h264Repo.isServiceRunning().fold(
                onSuccess = { running ->
                    h264ServiceStatus = if (running) ServiceStatus.RUNNING else ServiceStatus.STOPPED
                    Log.d("H264VideoScreen", "H264 service status: $h264ServiceStatus (attempt ${attempt+1})")
                },
                onFailure = {
                    Log.w("H264VideoScreen", "isServiceRunning failed (attempt ${attempt+1}): ${it.message}")
                    if (attempt == 2) h264ServiceStatus = ServiceStatus.ERROR
                }
            )
            if (h264ServiceStatus != ServiceStatus.ERROR) break
            delay(1000)
        }
        
        // 检查 HUD 服务（带重试）
        for (attempt in 0 until 3) {
            hudRepo.isHudRunning().fold(
                onSuccess = { running ->
                    hudServiceStatus = if (running) ServiceStatus.RUNNING else ServiceStatus.STOPPED
                    Log.d("H264VideoScreen", "HUD service status: $hudServiceStatus (attempt ${attempt+1})")
                },
                onFailure = {
                    Log.w("H264VideoScreen", "isHudRunning failed (attempt ${attempt+1}): ${it.message}")
                    if (attempt == 2) hudServiceStatus = ServiceStatus.ERROR
                }
            )
            if (hudServiceStatus != ServiceStatus.ERROR) break
            delay(1000)
        }
        
        isCheckingServices = false
    }

    // H264 视频流连接
    LaunchedEffect(camera, retryKey, h264ServiceStatus) {
        if (c3Ip.isNullOrBlank() || h264ServiceStatus != ServiceStatus.RUNNING) {
            return@LaunchedEffect
        }

        error = null
        Log.d("H264VideoScreen", "H264 service is running, connecting for camera=$camera")

        // 连接到 WebSocket
        h264Repo.connect(
            host = c3Ip,
            camera = camera.key,
            onFrame = { h264Data ->
                // 收到 H264 帧，喂给解码器
                scope.launch(Dispatchers.IO) {
                    val codec = decoder.value
                    if (codec != null) {
                        try {
                            // 计算帧率
                            val now = System.currentTimeMillis()
                            if (now - lastFrameTime >= 1000) {
                                // 这里简化处理，实际应该统计每秒帧数
                                fps = 30 // 假设 30fps，实际需要更精确的统计
                            }
                            lastFrameTime = now

                            // 喂给解码器
                            feedH264Frame(codec, h264Data)
                        } catch (e: Exception) {
                            Log.w("H264VideoScreen", "Failed to feed frame: ${e.message}")
                        }
                    }
                }
            },
            onError = { errorMsg ->
                Log.e("H264VideoScreen", "H264 stream error: $errorMsg")
                scope.launch(Dispatchers.Main) {
                    error = "H264 视频流连接失败: $errorMsg"
                }
            }
        )
    }

    // HUD 数据轮询（独立于视频流）
    LaunchedEffect(retryKey, hudServiceStatus) {
        if (c3Ip.isNullOrBlank() || hudServiceStatus != ServiceStatus.RUNNING) {
            return@LaunchedEffect
        }
        
        delay(1000)
        Log.d("H264VideoScreen", "HUD service is running, starting HUD data poll")
        
        while (true) {
            hudRepo.fetchHudData(c3Ip).fold(
                onSuccess = { hudData = it },
                onFailure = { Log.w("H264VideoScreen", "HUD fetch failed: ${it.message}") }
            )
            delay(500)
        }
    }

    // 离开页面时清理
    DisposableEffect(Unit) {
        onDispose {
            h264Repo.disconnect()
            decoder.value?.release()
        }
    }

    // UI 布局
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // 摄像头选择器
        CameraSelector(
            selected = camera,
            onSelect = {
                if (it != camera) {
                    camera = it
                    error = null
                    retryKey++
                }
            },
            sshManager = sshManager,
            h264ServiceStatus = h264ServiceStatus,
            hudServiceStatus = hudServiceStatus,
            onRefreshStatus = {
                scope.launch {
                    isCheckingServices = true
                    h264Repo.isServiceRunning().fold(
                        onSuccess = { h264ServiceStatus = if (it) ServiceStatus.RUNNING else ServiceStatus.STOPPED },
                        onFailure = { h264ServiceStatus = ServiceStatus.ERROR }
                    )
                    hudRepo.isHudRunning().fold(
                        onSuccess = { hudServiceStatus = if (it) ServiceStatus.RUNNING else ServiceStatus.STOPPED },
                        onFailure = { hudServiceStatus = ServiceStatus.ERROR }
                    )
                    isCheckingServices = false
                }
            },
            onStartH264 = {
                scope.launch {
                    h264ServiceStatus = ServiceStatus.STARTING
                    h264Repo.startService().fold(
                        onSuccess = {
                            h264ServiceStatus = ServiceStatus.RUNNING
                            retryKey++
                        },
                        onFailure = {
                            h264ServiceStatus = ServiceStatus.ERROR
                            error = "启动 H264 服务失败: ${it.message}"
                        }
                    )
                }
            },
            onStopH264 = {
                scope.launch {
                    h264Repo.stopService()
                    h264ServiceStatus = ServiceStatus.STOPPED
                }
            },
            onStartHud = {
                scope.launch {
                    hudServiceStatus = ServiceStatus.STARTING
                    hudRepo.startHudServer().fold(
                        onSuccess = { hudServiceStatus = ServiceStatus.RUNNING },
                        onFailure = {
                            hudServiceStatus = ServiceStatus.ERROR
                            error = "启动 HUD 服务失败: ${it.message}"
                        }
                    )
                }
            },
            onStopHud = {
                scope.launch {
                    hudRepo.stopHudServer()
                    hudServiceStatus = ServiceStatus.STOPPED
                    hudData = null
                }
            },
            h264Repo = h264Repo,
            hudRepo = hudRepo
        )

        Spacer(Modifier.height(16.dp))

        // 视频显示区域
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            contentAlignment = Alignment.Center
        ) {
            when {
                c3Ip.isNullOrBlank() -> {
                    ErrorCard(
                        message = "无法获取 C3 设备 IP 地址\n请确认已通过 SSH 连接到 C3",
                        onRetry = { retryKey++ }
                    )
                }

                error != null -> {
                    ErrorCard(
                        message = error!!,
                        onRetry = {
                            error = null
                            retryKey++
                        }
                    )
                }

                h264ServiceStatus == ServiceStatus.STOPPED -> {
                    Text(
                        text = "视频流服务未运行\n请点击右侧状态图标启动服务",
                        color = Slate400,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }

                else -> {
                    // H264 视频显示
                    H264VideoSurface(
                        surfaceHolder = { s -> surface.value = s },
                        onDecoderConfigured = { codec -> decoder.value = codec },
                        isRunning = h264ServiceStatus == ServiceStatus.RUNNING
                    )
                    
                    // HUD 叠加层
                    if (showHud) {
                        hudData?.let { data ->
                            HudOverlay(
                                hudData = data,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // FPS 指示器
                    Surface(
                        color = Color(0xAA000000),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Text(
                            "$fps fps",
                            color = if (fps > 0) Green500 else Slate400,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    // HUD 开关
                    Surface(
                        color = Color(0xAA000000),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clickable { showHud = !showHud }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (showHud) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "切换 HUD",
                                tint = if (showHud) Green500 else Slate400,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "HUD",
                                color = if (showHud) Green500 else Slate400,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 摄像头标签
                    Surface(
                        color = Color(0xAA000000),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    ) {
                        Text(
                            camera.title,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * H264 视频显示 Surface
 */
@Composable
private fun H264VideoSurface(
    surfaceHolder: (Surface) -> Unit,
    onDecoderConfigured: (MediaCodec) -> Unit,
    isRunning: Boolean
) {
    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                surfaceTexture?.let { st ->
                    val surface = Surface(st)
                    surfaceHolder(surface)
                    
                    // 配置解码器
                    val codec = try {
                        val c = MediaCodec.createDecoderByType("video/avc").apply {
                            val format = MediaFormat.createVideoFormat("video/avc", 1280, 720).apply {
                                setInteger(MediaFormat.KEY_COLOR_FORMAT, 
                                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                            }
                            configure(format, surface, null, 0)
                            start()
                        }
                        onDecoderConfigured(c)
                        Log.d("H264VideoSurface", "Decoder configured successfully")
                        c
                    } catch (e: Exception) {
                        Log.e("H264VideoSurface", "Failed to configure decoder: ${e.message}")
                        null
                    }
                }
            }
        },
        update = { view ->
            // 当状态变化时，可以重新配置解码器
        }
    )
}

/**
 * 喂 H264 帧给解码器
 */
private fun feedH264Frame(codec: MediaCodec, h264Data: ByteArray) {
    try {
        val index = codec.dequeueInputBuffer(10000)
        if (index >= 0) {
            val inputBuffer = codec.getInputBuffer(index)
            inputBuffer?.clear()
            inputBuffer?.put(h264Data)
            
            val timestamp = System.nanoTime() / 1000
            
            codec.queueInputBuffer(
                index,
                0,
                h264Data.size,
                timestamp,
                0
            )
            
            // 处理输出
            processDecoderOutput(codec)
        }
    } catch (e: Exception) {
        Log.w("H264VideoSurface", "Failed to feed frame: ${e.message}")
    }
}

/**
 * 处理解码器输出
 */
private fun processDecoderOutput(codec: MediaCodec) {
    try {
        val info = MediaCodec.BufferInfo()
        
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            
            when {
                index >= 0 -> {
                    codec.releaseOutputBuffer(index, true)
                    
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d("H264VideoSurface", "End of stream")
                        return
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d("H264VideoSurface", "Output format changed")
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    break
                }
            }
        }
    } catch (e: Exception) {
        Log.w("H264VideoSurface", "Failed to process output: ${e.message}")
    }
}

/**
 * 摄像头选择器（复用现有逻辑）
 */
@Composable
private fun CameraSelector(
    selected: H264CameraType,
    onSelect: (H264CameraType) -> Unit,
    sshManager: SshManager,
    h264ServiceStatus: ServiceStatus,
    hudServiceStatus: ServiceStatus,
    onRefreshStatus: () -> Unit,
    onStartH264: () -> Unit,
    onStopH264: () -> Unit,
    onStartHud: () -> Unit,
    onStopHud: () -> Unit,
    h264Repo: H264VideoRepository,
    hudRepo: HudDataRepository
) {
    val scope = rememberCoroutineScope()
    var showServiceMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 摄像头切换按钮
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Slate100)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            H264CameraType.entries.forEach { c ->
                val isSel = c == selected
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = if (isSel) Teal500 else Color.Transparent,
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(c) }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                    ) {
                        Text(
                            c.title,
                            color = if (isSel) Color.White else Slate700,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            c.desc,
                            color = if (isSel) Color(0xFFCCFBF1) else Slate400,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
        
        // 服务状态指示区域
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Slate100,
            modifier = Modifier.clickable { showServiceMenu = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // H264 视频流状态灯
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when (h264ServiceStatus) {
                                    ServiceStatus.RUNNING -> Color(0xFF10B981)
                                    ServiceStatus.STOPPED -> Color(0xFFEF4444)
                                    ServiceStatus.STARTING -> Color(0xFFF59E0B)
                                    else -> Color(0xFF6B7280)
                                }
                            )
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("📹", fontSize = 10.sp)
                }
                
                // HUD 数据服务状态灯
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when (hudServiceStatus) {
                                    ServiceStatus.RUNNING -> Color(0xFF10B981)
                                    ServiceStatus.STOPPED -> Color(0xFFEF4444)
                                    ServiceStatus.STARTING -> Color(0xFFF59E0B)
                                    else -> Color(0xFF6B7280)
                                }
                            )
                    )
                    Spacer(Modifier.height(2.dp))
                    Text("📊", fontSize = 10.sp)
                }
                
                // 设置图标
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "服务控制",
                    tint = Slate700,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
    
    // 服务控制菜单
    if (showServiceMenu) {
        H264ServiceControlDialog(
            h264ServiceStatus = h264ServiceStatus,
            hudServiceStatus = hudServiceStatus,
            onDismiss = { showServiceMenu = false },
            onRefreshStatus = onRefreshStatus,
            onStartH264 = onStartH264,
            onStopH264 = onStopH264,
            onStartHud = onStartHud,
            onStopHud = onStopHud,
            h264Repo = h264Repo,
            hudRepo = hudRepo,
            scope = scope
        )
    }
}

/**
 * H264 服务控制对话框
 */
@Composable
private fun H264ServiceControlDialog(
    h264ServiceStatus: ServiceStatus,
    hudServiceStatus: ServiceStatus,
    onDismiss: () -> Unit,
    onRefreshStatus: () -> Unit,
    onStartH264: () -> Unit,
    onStopH264: () -> Unit,
    onStartHud: () -> Unit,
    onStopHud: () -> Unit,
    h264Repo: H264VideoRepository,
    hudRepo: HudDataRepository,
    scope: CoroutineScope
) {
    var testingH264 by remember { mutableStateOf(false) }
    var h264TestResult by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("服务控制", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // H264 服务状态
                Text("H264 视频流服务", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                Text(
                    "状态: ${getServiceStatusText(h264ServiceStatus)}",
                    fontSize = 12.sp,
                    color = when (h264ServiceStatus) {
                        ServiceStatus.RUNNING -> Green500
                        ServiceStatus.STOPPED -> Color(0xFFDC2626)
                        else -> Slate500
                    }
                )
                Spacer(Modifier.height(8.dp))
                
                // 测试服务
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            testingH264 = true
                            h264TestResult = null
                            scope.launch {
                                val result = h264Repo.getServiceStatus()
                                h264TestResult = if (result.isSuccess) {
                                    result.getOrNull()
                                } else {
                                    "测试失败: ${result.exceptionOrNull()?.message}"
                                }
                                testingH264 = false
                            }
                        },
                        enabled = !testingH264
                    ) {
                        Text(if (testingH264) "测试中..." else "测试服务")
                    }
                    
                    if (h264ServiceStatus == ServiceStatus.RUNNING) {
                        Button(
                            onClick = onStopH264,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Text("停止", color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = onStartH264,
                            colors = ButtonDefaults.buttonColors(containerColor = Green500)
                        ) {
                            Text("启动", color = Color.White)
                        }
                    }
                }
                
                if (h264TestResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        h264TestResult!!,
                        fontSize = 11.sp,
                        color = Slate600,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
                
                Divider(color = Slate200)
                Spacer(Modifier.height(16.dp))
                
                // HUD 服务状态
                Text("HUD 数据服务", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                Text(
                    "状态: ${getServiceStatusText(hudServiceStatus)}",
                    fontSize = 12.sp,
                    color = when (hudServiceStatus) {
                        ServiceStatus.RUNNING -> Green500
                        ServiceStatus.STOPPED -> Color(0xFFDC2626)
                        else -> Slate500
                    }
                )
                Spacer(Modifier.height(8.dp))
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hudServiceStatus == ServiceStatus.RUNNING) {
                        Button(
                            onClick = onStopHud,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                        ) {
                            Text("停止", color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = onStartHud,
                            colors = ButtonDefaults.buttonColors(containerColor = Green500)
                        ) {
                            Text("启动", color = Color.White)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun getServiceStatusText(status: ServiceStatus): String = when (status) {
    ServiceStatus.RUNNING -> "运行中"
    ServiceStatus.STOPPED -> "已停止"
    ServiceStatus.STARTING -> "启动中"
    ServiceStatus.ERROR -> "错误"
    ServiceStatus.UNKNOWN -> "未知"
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Text("H264 视频预览", color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(12.dp), color = Slate100) {
            Text(
                text = message,
                color = Color(0xFFDC2626),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Teal500)
        ) {
            Text("重试", color = Color.White)
        }
    }
}
