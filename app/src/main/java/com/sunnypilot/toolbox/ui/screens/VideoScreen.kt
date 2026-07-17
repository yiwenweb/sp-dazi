package com.sunnypilot.toolbox.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.VideoStreamRepository
import com.sunnypilot.toolbox.data.repository.HudDataRepository
import com.sunnypilot.toolbox.data.repository.HudData
import com.sunnypilot.toolbox.ui.components.HudOverlay
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 摄像头实时流 — 通过 MJPEG HTTP 轮询观看 C3 摄像头画面。
 *
 * 原理: C3 端 mjpeg_stream.py 订阅 stream_encoderd 的 H264 帧,
 * 解码为 JPEG 通过 HTTP /frame 提供。Android 端原生 Bitmap 渲染。
 *
 * 优势: 绕过 WebRTC + WebView, 低延迟, 高兼容, 车机也能流畅运行。
 */
enum class CameraType(val key: String, val title: String, val desc: String) {
    ROAD("road", "主视角", "正前方，ACC/车道保持视线"),
    WIDE("wideRoad", "广角", "两侧变道/盲区视角"),
}

@Composable
fun VideoScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val videoRepo = remember { VideoStreamRepository(context, sshManager) }
    val hudRepo = remember { HudDataRepository(context, sshManager) }
    val c3Ip = remember { sshManager.connectedHost }

    var camera by remember { mutableStateOf(CameraType.ROAD) }
    var error by remember { mutableStateOf<String?>(null) }
    var isStarting by remember { mutableStateOf(true) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hudData by remember { mutableStateOf<HudData?>(null) }
    var showHud by remember { mutableStateOf(true) }  // HUD开关
    var fps by remember { mutableIntStateOf(0) }
    var retryKey by remember { mutableIntStateOf(0) }

    // 启动 C3 端 MJPEG 服务器 + HUD服务器
    LaunchedEffect(camera, retryKey) {
        if (c3Ip.isNullOrBlank()) {
            error = "无法获取 C3 设备 IP 地址\n请确认已通过 SSH 连接到 C3"
            isStarting = false
            return@LaunchedEffect
        }

        isStarting = true
        error = null
        frameBitmap = null
        hudData = null

        Log.d("VideoScreen", "Starting MJPEG stream, camera=$camera, ip=$c3Ip")
        videoRepo.enableStream(camera.key).fold(
            onSuccess = {
                // 等待 MJPEG 服务器启动
                delay(2000)
                isStarting = false
            },
            onFailure = { e ->
                error = "启动视频流失败: ${e.message}"
                isStarting = false
            }
        )
        
        // 启动HUD服务器
        Log.d("VideoScreen", "Starting HUD server")
        hudRepo.startHudServer().fold(
            onSuccess = { Log.d("VideoScreen", "HUD server started") },
            onFailure = { Log.w("VideoScreen", "HUD server start failed: ${it.message}") }
        )
    }

    // 离开页面时关闭流
    DisposableEffect(Unit) {
        onDispose {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { videoRepo.disableStream() }
                runCatching { hudRepo.stopHudServer() }
            }
        }
    }

    // 轮询HUD数据 (500ms = 2Hz)
    LaunchedEffect(retryKey, isStarting) {
        if (c3Ip.isNullOrBlank() || isStarting || error != null) return@LaunchedEffect
        
        delay(2000) // 等待HUD服务器启动
        Log.d("VideoScreen", "Starting HUD poll")
        
        while (true) {
            hudRepo.fetchHudData(c3Ip).fold(
                onSuccess = { hudData = it },
                onFailure = { Log.w("VideoScreen", "HUD fetch failed: ${it.message}") }
            )
            delay(500)
        }
    }

    // 轮询 JPEG 帧
    LaunchedEffect(camera, retryKey, isStarting) {
        if (c3Ip.isNullOrBlank() || isStarting || error != null) return@LaunchedEffect

        val frameUrl = videoRepo.frameUrl(c3Ip)
        Log.d("VideoScreen", "Starting frame poll: $frameUrl")

        var frameCount = 0
        var fpsTimer = System.currentTimeMillis()
        var consecutiveErrors = 0

        while (true) {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    fetchJpegFrame(frameUrl)
                }
                if (bitmap != null) {
                    frameBitmap = bitmap
                    consecutiveErrors = 0
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - fpsTimer >= 1000) {
                        fps = frameCount
                        frameCount = 0
                        fpsTimer = now
                    }
                } else {
                    consecutiveErrors++
                    if (consecutiveErrors > 10) {
                        error = "无法获取视频帧\n（确认车辆已启动且摄像头流已开启）"
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                if (consecutiveErrors > 15) {
                    error = "视频流连接失败: ${e.message}"
                    break
                }
            }
            delay(100) // ~10fps, 匹配 C3 端 FRAME_INTERVAL, 画面流畅
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // ===== 摄像头选择器 =====
        CameraSelector(
            selected = camera,
            onSelect = {
                if (it != camera) {
                    camera = it
                    error = null
                    isStarting = true
                    frameBitmap = null
                    retryKey++
                }
            },
            sshManager = sshManager
        )

        Spacer(Modifier.height(16.dp))

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
                    ErrorCard(message = error!!, onRetry = { retryKey++ })
                }

                else -> {
                    VideoCard(
                        bitmap = frameBitmap,
                        hudData = if (showHud) hudData else null,
                        camera = camera,
                        isStarting = isStarting,
                        fps = fps,
                        c3Ip = c3Ip,
                        showHud = showHud,
                        onToggleHud = { showHud = !showHud }
                    )
                }
            }
        }
    }
}

/** 从 HTTP URL 获取 JPEG 帧 */
private fun fetchJpegFrame(urlStr: String): Bitmap? {
    var conn: HttpURLConnection? = null
    return try {
        val url = URL(urlStr)
        conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.requestMethod = "GET"
        conn.useCaches = false

        if (conn.responseCode != 200) return null

        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) return null

        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        null
    } finally {
        conn?.disconnect()
    }
}

@Composable
private fun CameraSelector(
    selected: CameraType,
    onSelect: (CameraType) -> Unit,
    sshManager: SshManager
) {
    var showDiagnostics by remember { mutableStateOf(false) }
    
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
            CameraType.entries.forEach { c ->
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
                            .padding(horizontal = 14.dp, vertical = 10.dp)
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
        
        // 设置/诊断按钮
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Slate100,
            modifier = Modifier.clickable { showDiagnostics = true }
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "诊断设置",
                    tint = Slate700,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
    
    // 诊断对话框
    if (showDiagnostics) {
        DiagnosticsDialog(
            sshManager = sshManager,
            onDismiss = { showDiagnostics = false }
        )
    }
}

@Composable
private fun VideoCard(
    bitmap: Bitmap?,
    hudData: HudData?,
    camera: CameraType,
    isStarting: Boolean,
    fps: Int,
    c3Ip: String,
    showHud: Boolean,
    onToggleHud: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1A1A2E)),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "${camera.title} 实时画面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
                
                // HUD叠加层
                if (hudData != null) {
                    HudOverlay(
                        hudData = hudData,
                        modifier = Modifier.fillMaxSize()
                    )
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
                
                // HUD开关按钮
                Surface(
                    color = Color(0xAA000000),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clickable { onToggleHud() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (showHud) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "切换HUD",
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
                        .align(Alignment.TopStart)
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

            if (isStarting || bitmap == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Teal500,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            isStarting -> "正在启动 ${camera.title} 流..."
                            else -> "正在连接 ${camera.title}..."
                        },
                        color = Slate400,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(c3Ip, color = Slate600, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp)
    ) {
        Text("摄像头实时流", color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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

@Composable
private fun DiagnosticsDialog(
    sshManager: SshManager,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val videoRepo = remember { VideoStreamRepository(context, sshManager) }
    val hudRepo = remember { HudDataRepository(context, sshManager) }
    val c3Ip = remember { sshManager.connectedHost ?: "未连接" }
    
    var testingVideo by remember { mutableStateOf(false) }
    var testingHud by remember { mutableStateOf(false) }
    var redeployingScripts by remember { mutableStateOf(false) }
    var videoResult by remember { mutableStateOf<String?>(null) }
    var hudResult by remember { mutableStateOf<String?>(null) }
    var redeployResult by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = Teal500,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("视频流诊断", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // C3 IP 信息
                InfoRow(label = "C3 地址", value = c3Ip)
                Spacer(Modifier.height(12.dp))
                Divider(color = Slate200)
                Spacer(Modifier.height(16.dp))
                
                // 测试视频流服务
                Text("1. 测试视频流服务", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                Spacer(Modifier.height(8.dp))
                Text(
                    "检查 MJPEG 服务器是否正常运行，端口 5002 是否可访问",
                    fontSize = 12.sp,
                    color = Slate500,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        testingVideo = true
                        videoResult = null
                        scope.launch {
                            val result = StringBuilder()
                            
                            // 检查服务进程
                            sshManager.executeCommand("pgrep -af mjpeg_stream").fold(
                                onSuccess = { output ->
                                    if (output.trim().isEmpty()) {
                                        result.append("❌ MJPEG 服务未运行\n")
                                    } else {
                                        result.append("✓ MJPEG 服务进程: ${output.trim().take(50)}...\n")
                                    }
                                },
                                onFailure = { result.append("❌ 无法检查进程: ${it.message}\n") }
                            )
                            
                            // 检查端口
                            sshManager.executeCommand("netstat -tuln | grep 5002").fold(
                                onSuccess = { output ->
                                    if (output.contains("5002")) {
                                        result.append("✓ 端口 5002 正在监听\n")
                                    } else {
                                        result.append("❌ 端口 5002 未监听\n")
                                    }
                                },
                                onFailure = { result.append("❌ 无法检查端口\n") }
                            )
                            
                            // 测试健康检查接口
                            videoRepo.isStreamRunning().fold(
                                onSuccess = { running ->
                                    if (running) {
                                        result.append("✓ 服务响应正常\n")
                                    } else {
                                        result.append("⚠ 服务已启动但无法获取帧数据\n可能原因: stream_encoderd 未运行或车辆未启动\n")
                                    }
                                },
                                onFailure = { result.append("❌ 服务无响应: ${it.message}\n") }
                            )
                            
                            videoResult = result.toString()
                            testingVideo = false
                        }
                    },
                    enabled = !testingVideo,
                    colors = ButtonDefaults.buttonColors(containerColor = Blue500),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testingVideo) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (testingVideo) "测试中..." else "开始测试")
                }
                
                if (videoResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50
                    ) {
                        Text(
                            videoResult!!,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Slate700,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                Divider(color = Slate200)
                Spacer(Modifier.height(16.dp))
                
                // 测试 HUD 服务
                Text("2. 测试 HUD 数据服务", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                Spacer(Modifier.height(8.dp))
                Text(
                    "检查 HUD 数据服务器是否正常运行，端口 5003 是否可访问",
                    fontSize = 12.sp,
                    color = Slate500,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        testingHud = true
                        hudResult = null
                        scope.launch {
                            val result = StringBuilder()
                            
                            // 检查服务进程
                            sshManager.executeCommand("pgrep -af hud_data_server").fold(
                                onSuccess = { output ->
                                    if (output.trim().isEmpty()) {
                                        result.append("❌ HUD 服务未运行\n")
                                    } else {
                                        result.append("✓ HUD 服务进程: ${output.trim().take(50)}...\n")
                                    }
                                },
                                onFailure = { result.append("❌ 无法检查进程: ${it.message}\n") }
                            )
                            
                            // 检查端口
                            sshManager.executeCommand("netstat -tuln | grep 5003").fold(
                                onSuccess = { output ->
                                    if (output.contains("5003")) {
                                        result.append("✓ 端口 5003 正在监听\n")
                                    } else {
                                        result.append("❌ 端口 5003 未监听\n")
                                    }
                                },
                                onFailure = { result.append("❌ 无法检查端口\n") }
                            )
                            
                            // 测试 HUD 数据获取
                            hudRepo.fetchHudData(c3Ip).fold(
                                onSuccess = { data ->
                                    result.append("✓ HUD 数据获取成功\n")
                                    result.append("  速度: ${data.speed} km/h\n")
                                    result.append("  档位: ${data.gear}\n")
                                },
                                onFailure = { result.append("❌ HUD 数据获取失败: ${it.message}\n") }
                            )
                            
                            hudResult = result.toString()
                            testingHud = false
                        }
                    },
                    enabled = !testingHud,
                    colors = ButtonDefaults.buttonColors(containerColor = Blue500),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (testingHud) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (testingHud) "测试中..." else "开始测试")
                }
                
                if (hudResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50
                    ) {
                        Text(
                            hudResult!!,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Slate700,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                Divider(color = Slate200)
                Spacer(Modifier.height(16.dp))
                
                // 重新部署脚本
                Text("3. 重新部署脚本", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Slate900)
                Spacer(Modifier.height(8.dp))
                Text(
                    "从 App 重新上传 Python 脚本到 C3 设备（覆盖旧版本）",
                    fontSize = 12.sp,
                    color = Slate500,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        redeployingScripts = true
                        redeployResult = null
                        scope.launch {
                            val result = StringBuilder()
                            
                            try {
                                // 停止旧服务
                                sshManager.executeCommand("pkill -f mjpeg_stream; pkill -f hud_data_server")
                                delay(500)
                                
                                // 重新部署 MJPEG 脚本
                                val mjpegContent = context.assets.open("mjpeg_stream.py")
                                    .bufferedReader().use { it.readText() }
                                sshManager.writeTextFile(
                                    "/data/spapp/spyl/mjpeg_stream.py",
                                    mjpegContent
                                ).fold(
                                    onSuccess = { result.append("✓ mjpeg_stream.py 已更新\n") },
                                    onFailure = { result.append("❌ mjpeg_stream.py 更新失败: ${it.message}\n") }
                                )
                                
                                // 重新部署 HUD 脚本
                                val hudContent = context.assets.open("hud_data_server.py")
                                    .bufferedReader().use { it.readText() }
                                sshManager.writeTextFile(
                                    "/data/spapp/spyl/hud_data_server.py",
                                    hudContent
                                ).fold(
                                    onSuccess = { result.append("✓ hud_data_server.py 已更新\n") },
                                    onFailure = { result.append("❌ hud_data_server.py 更新失败: ${it.message}\n") }
                                )
                                
                                result.append("\n✓ 脚本部署完成！\n请返回重新启动视频流")
                                
                            } catch (e: Exception) {
                                result.append("❌ 部署失败: ${e.message}\n")
                            }
                            
                            redeployResult = result.toString()
                            redeployingScripts = false
                        }
                    },
                    enabled = !redeployingScripts,
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (redeployingScripts) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                            color = Color.White
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (redeployingScripts) "部署中..." else "重新部署")
                }
                
                if (redeployResult != null) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50
                    ) {
                        Text(
                            redeployResult!!,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = Slate700,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Teal500)
            }
        },
        modifier = Modifier.widthIn(max = 500.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Slate500)
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Slate900,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}
