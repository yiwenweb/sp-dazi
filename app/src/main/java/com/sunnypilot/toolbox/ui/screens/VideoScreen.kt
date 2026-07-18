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
import java.nio.ByteBuffer

/**
 * 摄像头实时流 — 通过 MJPEG HTTP 轮询观看 C3 摄像头画面。
 *
 * 原理: C3 端 mjpeg_stream.py 订阅 stream_encoderd 的 H264 帧,
 * 解码为 JPEG 通过 HTTP /frame 提供。Android 端原生 Bitmap 渲染。
 *
 * 优势: 绕过 WebRTC + WebView, 低延迟, 高兼容, 车机也能流畅运行。
 * 
 * 服务状态管理:
 * - 进入页面自动检查服务状态
 * - 如果服务已运行，直接连接（不杀进程）
 * - 如果服务未运行，提示用户启动
 * - 用户可通过设置菜单手动控制服务
 */
enum class CameraType(val key: String, val title: String, val desc: String) {
    ROAD("road", "主视角", "正前方，ACC/车道保持视线"),
    WIDE("wideRoad", "广角", "两侧变道/盲区视角"),
}

enum class ServiceStatus {
    UNKNOWN,    // 未知（未检查）
    RUNNING,    // 运行中
    STOPPED,    // 已停止
    STARTING,   // 启动中
    ERROR       // 错误
}

@Composable
fun VideoScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val videoRepo = remember { VideoStreamRepository(context, sshManager) }
    val hudRepo = remember { HudDataRepository(context, sshManager) }
    val c3Ip = remember { sshManager.connectedHost }

    var camera by remember { mutableStateOf(CameraType.ROAD) }
    var error by remember { mutableStateOf<String?>(null) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hudData by remember { mutableStateOf<HudData?>(null) }
    var showHud by remember { mutableStateOf(true) }
    var fps by remember { mutableIntStateOf(0) }
    var retryKey by remember { mutableIntStateOf(0) }
    
    // 双服务状态管理
    var mjpegServiceStatus by remember { mutableStateOf(ServiceStatus.UNKNOWN) }
    var hudServiceStatus by remember { mutableStateOf(ServiceStatus.UNKNOWN) }
    var isCheckingServices by remember { mutableStateOf(false) }

    // 进入页面时检查两个服务的状态
    LaunchedEffect(Unit) {
        if (c3Ip.isNullOrBlank()) {
            mjpegServiceStatus = ServiceStatus.ERROR
            hudServiceStatus = ServiceStatus.ERROR
            return@LaunchedEffect
        }
        
        isCheckingServices = true
        
        // 检查MJPEG服务（带重试，避免网络抖动误判）
        for (attempt in 0 until 3) {
            videoRepo.isStreamRunning().fold(
                onSuccess = { running ->
                    mjpegServiceStatus = if (running) ServiceStatus.RUNNING else ServiceStatus.STOPPED
                    Log.d("VideoScreen", "MJPEG service status: $mjpegServiceStatus (attempt ${attempt+1})")
                },
                onFailure = {
                    Log.w("VideoScreen", "isStreamRunning failed (attempt ${attempt+1}): ${it.message}")
                    if (attempt == 2) mjpegServiceStatus = ServiceStatus.ERROR
                }
            )
            if (mjpegServiceStatus != ServiceStatus.ERROR) break
            delay(1000)
        }
        
        // 检查HUD服务
        for (attempt in 0 until 3) {
            hudRepo.isHudRunning().fold(
                onSuccess = { running ->
                    hudServiceStatus = if (running) ServiceStatus.RUNNING else ServiceStatus.STOPPED
                    Log.d("VideoScreen", "HUD service status: $hudServiceStatus (attempt ${attempt+1})")
                },
                onFailure = {
                    Log.w("VideoScreen", "isHudRunning failed (attempt ${attempt+1}): ${it.message}")
                    if (attempt == 2) hudServiceStatus = ServiceStatus.ERROR
                }
            )
            if (hudServiceStatus != ServiceStatus.ERROR) break
            delay(1000)
        }
        
        isCheckingServices = false
    }

    // 仅当MJPEG服务运行时才启动视频流
    LaunchedEffect(camera, retryKey, mjpegServiceStatus) {
        if (c3Ip.isNullOrBlank() || mjpegServiceStatus != ServiceStatus.RUNNING) {
            return@LaunchedEffect
        }

        error = null
        frameBitmap = null

        Log.d("VideoScreen", "MJPEG service is running, starting video stream for camera=$camera")
    }
    
    // 仅当HUD服务运行时才获取HUD数据（独立于视频流，不受 error 影响）
    LaunchedEffect(retryKey, hudServiceStatus) {
        if (c3Ip.isNullOrBlank() || hudServiceStatus != ServiceStatus.RUNNING) {
            return@LaunchedEffect
        }
        
        delay(1000)
        Log.d("VideoScreen", "HUD service is running, starting HUD data poll")
        
        while (true) {
            hudRepo.fetchHudData(c3Ip).fold(
                onSuccess = { hudData = it },
                onFailure = { Log.w("VideoScreen", "HUD fetch failed: ${it.message}") }
            )
            delay(500)
        }
    }

    // 离开页面时清理（但不关闭服务，让服务继续运行）
    DisposableEffect(Unit) {
        onDispose {
            // 不再关闭任何服务，让它们保持运行
        }
    }

    // 轮询 JPEG 帧
    LaunchedEffect(camera, retryKey, mjpegServiceStatus) {
        if (c3Ip.isNullOrBlank() || mjpegServiceStatus != ServiceStatus.RUNNING || error != null) {
            return@LaunchedEffect
        }

        val frameUrl = videoRepo.frameUrl(c3Ip)
        Log.d("VideoScreen", "Starting frame poll: $frameUrl")

        var frameCount = 0
        var fpsTimer = System.currentTimeMillis()
        var consecutiveErrors = 0
        var lastSuccessTime = System.currentTimeMillis()

        while (true) {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    fetchJpegFrame(frameUrl)
                }
                if (bitmap != null) {
                    frameBitmap = bitmap
                    consecutiveErrors = 0
                    lastSuccessTime = System.currentTimeMillis()
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - fpsTimer >= 1000) {
                        fps = frameCount
                        frameCount = 0
                        fpsTimer = now
                        Log.d("VideoScreen", "FPS: $fps")
                    }
                } else {
                    consecutiveErrors++
                    val timeSinceSuccess = System.currentTimeMillis() - lastSuccessTime
                    Log.w("VideoScreen", "Failed to fetch frame (errors: $consecutiveErrors, time since success: ${timeSinceSuccess}ms)")
                    
                    if (consecutiveErrors > 10 && timeSinceSuccess > 3000) {
                        error = "无法获取视频帧\n\n可能原因：\n1. MJPEG服务未启动\n2. 车辆未启动\n3. 网络连接问题\n\n建议：点击设置图标运行诊断"
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveErrors++
                Log.e("VideoScreen", "Frame fetch exception (errors: $consecutiveErrors)", e)
                if (consecutiveErrors > 15) {
                    error = "视频流连接失败: ${e.message}\n\nC3 IP: $c3Ip\n端口: 5002\n\n建议运行诊断检查"
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
                    frameBitmap = null
                    
                    // 如果MJPEG服务运行中，重启切换摄像头
                    if (mjpegServiceStatus == ServiceStatus.RUNNING) {
                        scope.launch {
                            mjpegServiceStatus = ServiceStatus.STARTING
                            videoRepo.restartStream(it.key).fold(
                                onSuccess = {
                                    delay(2000)  // 等 aiohttp 重新绑定端口
                                    mjpegServiceStatus = ServiceStatus.RUNNING
                                    retryKey++
                                },
                                onFailure = { e ->
                                    mjpegServiceStatus = ServiceStatus.ERROR
                                    error = "切换摄像头失败: ${e.message}"
                                }
                            )
                        }
                    }
                }
            },
            sshManager = sshManager,
            mjpegServiceStatus = mjpegServiceStatus,
            hudServiceStatus = hudServiceStatus,
            onRefreshStatus = {
                scope.launch {
                    isCheckingServices = true
                    
                    videoRepo.isStreamRunning().fold(
                        onSuccess = { mjpegServiceStatus = if (it) ServiceStatus.RUNNING else ServiceStatus.STOPPED },
                        onFailure = { mjpegServiceStatus = ServiceStatus.ERROR }
                    )
                    
                    hudRepo.isHudRunning().fold(
                        onSuccess = { hudServiceStatus = if (it) ServiceStatus.RUNNING else ServiceStatus.STOPPED },
                        onFailure = { hudServiceStatus = ServiceStatus.ERROR }
                    )
                    
                    isCheckingServices = false
                }
            },
            onStartMjpeg = {
                scope.launch {
                    mjpegServiceStatus = ServiceStatus.STARTING
                    videoRepo.enableStream(camera.key).fold(
                        onSuccess = {
                            // 等 aiohttp 真正开始监听端口，避免启动后立刻轮询失败
                            delay(2000)
                            mjpegServiceStatus = ServiceStatus.RUNNING
                            retryKey++
                        },
                        onFailure = {
                            mjpegServiceStatus = ServiceStatus.ERROR
                            error = "启动MJPEG服务失败: ${it.message}"
                        }
                    )
                }
            },
            onStopMjpeg = {
                scope.launch {
                    videoRepo.disableStream()
                    mjpegServiceStatus = ServiceStatus.STOPPED
                    frameBitmap = null
                }
            },
            onStartHud = {
                scope.launch {
                    hudServiceStatus = ServiceStatus.STARTING
                    hudRepo.startHudServer().fold(
                        onSuccess = {
                            hudServiceStatus = ServiceStatus.RUNNING
                        },
                        onFailure = {
                            hudServiceStatus = ServiceStatus.ERROR
                            error = "启动HUD服务失败: ${it.message}"
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
            videoRepo = videoRepo,
            hudRepo = hudRepo
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
                    ErrorCard(message = error!!, onRetry = {
                        error = null
                        frameBitmap = null
                        retryKey++
                    })
                }

                else -> {
                    VideoCard(
                        bitmap = frameBitmap,
                        hudData = if (showHud) hudData else null,
                        camera = camera,
                        mjpegServiceStatus = mjpegServiceStatus,
                        hudServiceStatus = hudServiceStatus,
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
        conn.setRequestProperty("Connection", "close")  // 避免连接复用问题

        val responseCode = conn.responseCode
        if (responseCode != 200) {
            Log.w("VideoScreen", "HTTP $responseCode from $urlStr")
            return null
        }

        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) {
            Log.w("VideoScreen", "Empty response from $urlStr")
            return null
        }

        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
            Log.v("VideoScreen", "Frame decoded: ${it.width}x${it.height}, ${bytes.size} bytes")
        }
    } catch (e: java.net.SocketTimeoutException) {
        Log.w("VideoScreen", "Timeout fetching frame from $urlStr")
        null
    } catch (e: java.net.ConnectException) {
        Log.w("VideoScreen", "Connection refused: $urlStr - service not running?")
        null
    } catch (e: Exception) {
        Log.w("VideoScreen", "Error fetching frame: ${e.javaClass.simpleName}: ${e.message}")
        null
    } finally {
        conn?.disconnect()
    }
}

@Composable
private fun CameraSelector(
    selected: CameraType,
    onSelect: (CameraType) -> Unit,
    sshManager: SshManager,
    mjpegServiceStatus: ServiceStatus,
    hudServiceStatus: ServiceStatus,
    onRefreshStatus: () -> Unit,
    onStartMjpeg: () -> Unit,
    onStopMjpeg: () -> Unit,
    onStartHud: () -> Unit,
    onStopHud: () -> Unit,
    videoRepo: VideoStreamRepository,
    hudRepo: HudDataRepository
) {
    val scope = rememberCoroutineScope()
    var showDiagnostics by remember { mutableStateOf(false) }
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
                // MJPEG视频流状态灯
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                when (mjpegServiceStatus) {
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
                
                // HUD数据服务状态灯
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
        DualServiceControlDialog(
            mjpegServiceStatus = mjpegServiceStatus,
            hudServiceStatus = hudServiceStatus,
            onDismiss = { showServiceMenu = false },
            onRefreshStatus = onRefreshStatus,
            onStartMjpeg = onStartMjpeg,
            onStopMjpeg = onStopMjpeg,
            onStartHud = onStartHud,
            onStopHud = onStopHud,
            onShowDiagnostics = {
                showServiceMenu = false
                showDiagnostics = true
            },
            videoRepo = videoRepo,
            hudRepo = hudRepo,
            scope = scope
        )
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
    mjpegServiceStatus: ServiceStatus,
    hudServiceStatus: ServiceStatus,
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

            if (mjpegServiceStatus != ServiceStatus.RUNNING || bitmap == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Teal500,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when (mjpegServiceStatus) {
                            ServiceStatus.STARTING -> "正在启动视频流服务..."
                            ServiceStatus.STOPPED -> "视频流服务未运行\n请点击右侧状态图标启动服务"
                            ServiceStatus.RUNNING -> "正在连接 ${camera.title}..."
                            ServiceStatus.ERROR -> "视频流服务异常\n请运行诊断检查"
                            ServiceStatus.UNKNOWN -> "正在检查服务状态..."
                        },
                        color = Slate400,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    
                    // HUD服务状态提示
                    if (hudServiceStatus != ServiceStatus.RUNNING && mjpegServiceStatus == ServiceStatus.RUNNING) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "提示: HUD数据服务未运行",
                            color = Color(0xFFF59E0B),
                            fontSize = 11.sp
                        )
                    }
                    
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
                            
                            // 1. 检查服务进程
                            sshManager.executeCommand("ps aux | grep '[m]jpeg_stream'").fold(
                                onSuccess = { output ->
                                    if (output.trim().isEmpty()) {
                                        result.append("❌ MJPEG 服务未运行\n")
                                    } else {
                                        val lines = output.trim().split("\n")
                                        result.append("✓ MJPEG 服务运行中 (${lines.size}个进程)\n")
                                        lines.forEach { line ->
                                            // 提取 PID (第二列)
                                            val parts = line.trim().split(Regex("\\s+"))
                                            if (parts.size > 1) {
                                                result.append("  PID: ${parts[1]}\n")
                                            }
                                        }
                                    }
                                },
                                onFailure = { e -> 
                                    result.append("❌ 无法检测进程: ${e.message}\n")
                                }
                            )
                            
                            // 2. 检查端口
                            sshManager.executeCommand("netstat -tuln 2>/dev/null | grep ':5002 ' || ss -tuln 2>/dev/null | grep ':5002 '").fold(
                                onSuccess = { output ->
                                    if (output.contains("5002")) {
                                        result.append("✓ 端口 5002 正在监听\n")
                                        val lines = output.trim().split("\n")
                                        lines.forEach { line ->
                                            if (line.contains("5002")) {
                                                result.append("  ${line.trim().take(50)}\n")
                                            }
                                        }
                                    } else {
                                        result.append("❌ 端口 5002 未监听\n")
                                    }
                                },
                                onFailure = { e -> 
                                    result.append("⚠ 无法检查端口: ${e.message}\n")
                                }
                            )
                            
                            // 3. HTTP 健康检查 - 直接从Android端访问
                            withContext(Dispatchers.IO) {
                                try {
                                    val healthUrl = videoRepo.healthUrl(c3Ip)
                                    val url = URL(healthUrl)
                                    val conn = url.openConnection() as HttpURLConnection
                                    conn.connectTimeout = 3000
                                    conn.readTimeout = 3000
                                    conn.requestMethod = "GET"
                                    
                                    val responseCode = conn.responseCode
                                    if (responseCode == 200) {
                                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                                        result.append("✓ HTTP 服务响应正常 (200)\n")
                                        result.append("  响应: ${response.trim().take(50)}\n")
                                    } else {
                                        result.append("⚠ HTTP 服务返回: $responseCode\n")
                                    }
                                    conn.disconnect()
                                } catch (e: Exception) {
                                    result.append("❌ HTTP 服务无响应: ${e.message}\n")
                                }
                            }
                            
                            // 4. 测试实际帧获取 - 直接从Android端访问
                            withContext(Dispatchers.IO) {
                                try {
                                    val frameUrl = videoRepo.frameUrl(c3Ip)
                                    val bitmap = fetchJpegFrame(frameUrl)
                                    if (bitmap != null) {
                                        result.append("✓ 视频帧获取成功\n")
                                        result.append("  分辨率: ${bitmap.width}x${bitmap.height}\n")
                                    } else {
                                        result.append("❌ 视频帧获取失败\n")
                                    }
                                } catch (e: Exception) {
                                    result.append("❌ 视频帧接口错误: ${e.message}\n")
                                }
                            }
                            
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
                            
                            // 1. 检查服务进程
                            sshManager.executeCommand("ps aux | grep '[h]ud_data_server'").fold(
                                onSuccess = { output ->
                                    if (output.trim().isEmpty()) {
                                        result.append("❌ HUD 服务未运行\n")
                                    } else {
                                        val lines = output.trim().split("\n")
                                        result.append("✓ HUD 服务运行中 (${lines.size}个进程)\n")
                                        lines.forEach { line ->
                                            val parts = line.trim().split(Regex("\\s+"))
                                            if (parts.size > 1) {
                                                result.append("  PID: ${parts[1]}\n")
                                            }
                                        }
                                    }
                                },
                                onFailure = { e -> 
                                    result.append("❌ 无法检测进程: ${e.message}\n")
                                }
                            )
                            
                            // 2. 检查端口
                            sshManager.executeCommand("netstat -tuln 2>/dev/null | grep ':5003 ' || ss -tuln 2>/dev/null | grep ':5003 '").fold(
                                onSuccess = { output ->
                                    if (output.contains("5003")) {
                                        result.append("✓ 端口 5003 正在监听\n")
                                        val lines = output.trim().split("\n")
                                        lines.forEach { line ->
                                            if (line.contains("5003")) {
                                                result.append("  ${line.trim().take(50)}\n")
                                            }
                                        }
                                    } else {
                                        result.append("❌ 端口 5003 未监听\n")
                                    }
                                },
                                onFailure = { e -> 
                                    result.append("⚠ 无法检查端口: ${e.message}\n")
                                }
                            )
                            
                            // 3. HTTP 健康检查 - 直接从Android端访问
                            withContext(Dispatchers.IO) {
                                try {
                                    val healthUrl = "http://$c3Ip:5003/health"
                                    val url = URL(healthUrl)
                                    val conn = url.openConnection() as HttpURLConnection
                                    conn.connectTimeout = 3000
                                    conn.readTimeout = 3000
                                    conn.requestMethod = "GET"
                                    
                                    val responseCode = conn.responseCode
                                    if (responseCode == 200) {
                                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                                        result.append("✓ HTTP 服务响应正常 (200)\n")
                                        result.append("  响应: ${response.trim().take(50)}\n")
                                    } else {
                                        result.append("⚠ HTTP 服务返回: $responseCode\n")
                                    }
                                    conn.disconnect()
                                } catch (e: Exception) {
                                    result.append("❌ HTTP 服务无响应: ${e.message}\n")
                                }
                            }
                            
                            // 4. 测试 HUD 数据获取 - 直接使用 repository
                            hudRepo.fetchHudData(c3Ip).fold(
                                onSuccess = { data ->
                                    result.append("✓ HUD 数据获取成功\n")
                                    result.append("  速度: ${data.speed} km/h\n")
                                    result.append("  档位: ${data.gear}\n")
                                    result.append("  方向盘角度: ${data.steeringAngle}°\n")
                                    if (data.leadDistance != null) {
                                        result.append("  前车距离: ${data.leadDistance}m\n")
                                    }
                                },
                                onFailure = { e -> 
                                    result.append("❌ HUD 数据获取失败: ${e.message}\n")
                                }
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

@Composable
private fun DualServiceControlDialog(
    mjpegServiceStatus: ServiceStatus,
    hudServiceStatus: ServiceStatus,
    onDismiss: () -> Unit,
    onRefreshStatus: () -> Unit,
    onStartMjpeg: () -> Unit,
    onStopMjpeg: () -> Unit,
    onStartHud: () -> Unit,
    onStopHud: () -> Unit,
    onShowDiagnostics: () -> Unit,
    videoRepo: VideoStreamRepository,
    hudRepo: HudDataRepository,
    scope: CoroutineScope
) {
    var redeployingMjpeg by remember { mutableStateOf(false) }
    var redeployingHud by remember { mutableStateOf(false) }
    var redeployResult by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = null,
                    tint = Teal500,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("服务控制", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // MJPEG视频流服务
                ServiceCard(
                    icon = "📹",
                    name = "视频流服务",
                    port = 5002,
                    status = mjpegServiceStatus,
                    onStart = onStartMjpeg,
                    onStop = onStopMjpeg,
                    onRedeploy = {
                        redeployingMjpeg = true
                        redeployResult = null
                        scope.launch {
                            videoRepo.redeployScript().fold(
                                onSuccess = { result ->
                                    redeployResult = result
                                    redeployingMjpeg = false
                                },
                                onFailure = { e ->
                                    redeployResult = "❌ 部署失败: ${e.message}"
                                    redeployingMjpeg = false
                                }
                            )
                        }
                    },
                    isRedeploying = redeployingMjpeg
                )
                
                Divider(color = Slate200)
                
                // HUD数据服务
                ServiceCard(
                    icon = "📊",
                    name = "HUD数据服务",
                    port = 5003,
                    status = hudServiceStatus,
                    onStart = onStartHud,
                    onStop = onStopHud,
                    onRedeploy = {
                        redeployingHud = true
                        redeployResult = null
                        scope.launch {
                            hudRepo.redeployScript().fold(
                                onSuccess = { result ->
                                    redeployResult = result
                                    redeployingHud = false
                                },
                                onFailure = { e ->
                                    redeployResult = "❌ 部署失败: ${e.message}"
                                    redeployingHud = false
                                }
                            )
                        }
                    },
                    isRedeploying = redeployingHud
                )
                
                // 部署结果显示
                if (redeployResult != null) {
                    Divider(color = Slate200)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (redeployResult!!.startsWith("✓")) Color(0xFFD1FAE5) else Color(0xFFFEE2E2)
                    ) {
                        Text(
                            redeployResult!!,
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = if (redeployResult!!.startsWith("✓")) Color(0xFF10B981) else Color(0xFFEF4444),
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                
                Divider(color = Slate200)
                
                // 全局操作
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefreshStatus,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal500)
                    ) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新", fontSize = 13.sp)
                    }
                    
                    OutlinedButton(
                        onClick = onShowDiagnostics,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Teal500)
                    ) {
                        Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("诊断", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Teal500)
            }
        },
        modifier = Modifier.widthIn(max = 450.dp)
    )
}

@Composable
private fun ServiceCard(
    icon: String,
    name: String,
    port: Int,
    status: ServiceStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRedeploy: () -> Unit,
    isRedeploying: Boolean
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when (status) {
            ServiceStatus.RUNNING -> Color(0xFFD1FAE5)
            ServiceStatus.STOPPED -> Color(0xFFFEE2E2)
            ServiceStatus.STARTING -> Color(0xFFFEF3C7)
            else -> Slate100
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate900
                        )
                        Text(
                            "端口: $port",
                            fontSize = 11.sp,
                            color = Slate500
                        )
                    }
            }
                
                Text(
                    when (status) {
                        ServiceStatus.RUNNING -> "✓ 运行中"
                        ServiceStatus.STOPPED -> "✗ 已停止"
                        ServiceStatus.STARTING -> "⟳ 启动中"
                        ServiceStatus.ERROR -> "⚠ 错误"
                        ServiceStatus.UNKNOWN -> "? 未知"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = when (status) {
                        ServiceStatus.RUNNING -> Color(0xFF10B981)
                        ServiceStatus.STOPPED -> Color(0xFFEF4444)
                        ServiceStatus.STARTING -> Color(0xFFF59E0B)
                        else -> Slate700
                    }
                )
            }
            
            Spacer(Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onStart,
                    enabled = status == ServiceStatus.STOPPED || status == ServiceStatus.ERROR,
                    colors = ButtonDefaults.buttonColors(containerColor = Green500),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("启动", fontSize = 13.sp)
                }
                
                Button(
                    onClick = onStop,
                    enabled = status == ServiceStatus.RUNNING,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("停止", fontSize = 13.sp)
                }
                
                OutlinedButton(
                    onClick = onRedeploy,
                    enabled = !isRedeploying,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isRedeploying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Filled.Upload, null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("重新部署", fontSize = 11.sp)
                }
            }
        }
    }
}
