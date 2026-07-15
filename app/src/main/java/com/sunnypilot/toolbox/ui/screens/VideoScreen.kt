package com.sunnypilot.toolbox.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val c3Ip = remember { sshManager.connectedHost }

    var camera by remember { mutableStateOf(CameraType.ROAD) }
    var error by remember { mutableStateOf<String?>(null) }
    var isStarting by remember { mutableStateOf(true) }
    var frameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fps by remember { mutableIntStateOf(0) }
    var retryKey by remember { mutableIntStateOf(0) }

    // 启动 C3 端 MJPEG 服务器
    LaunchedEffect(camera, retryKey) {
        if (c3Ip.isNullOrBlank()) {
            error = "无法获取 C3 设备 IP 地址\n请确认已通过 SSH 连接到 C3"
            isStarting = false
            return@LaunchedEffect
        }

        isStarting = true
        error = null
        frameBitmap = null

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
    }

    // 离开页面时关闭流
    DisposableEffect(Unit) {
        onDispose {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { videoRepo.disableStream() }
            }
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
            delay(150) // ~6 fps, 匹配 C3 端 FRAME_INTERVAL, 降低双方 CPU
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
            }
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
                        camera = camera,
                        isStarting = isStarting,
                        fps = fps,
                        c3Ip = c3Ip
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
    onSelect: (CameraType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
}

@Composable
private fun VideoCard(
    bitmap: Bitmap?,
    camera: CameraType,
    isStarting: Boolean,
    fps: Int,
    c3Ip: String
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
