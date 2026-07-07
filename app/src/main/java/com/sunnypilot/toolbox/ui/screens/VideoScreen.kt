package com.sunnypilot.toolbox.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.VideoStreamRepository
import com.sunnypilot.toolbox.ui.theme.*
import com.sunnypilot.toolbox.ui.util.WebrtcHtml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 视频预览 - 支持两种查看方式：
 *
 * 1) 屏幕预览（ScreenStream，端口 8083）
 *    - C3 Qt UI 内置 MJPEG 屏幕截图流，显示"和 C3 屏幕一样的内容"（含 HUD/按钮）
 *    - offroad/onroad 均可用；但 Wayland 下摄像头图层可能无法抓取
 *    - 有一定 CPU 开销（截屏 + JPEG 编码）
 *
 * 2) 摄像头流（WebRTC，端口 5001）
 *    - 复用 openpilot 官方 stream_encoderd 硬件 H264 编码 + webrtcd
 *    - 几乎不占 CPU/GPU（骁龙 845 Venus 硬编），高帧率高画质
 *    - 仅摄像头画面，无 UI 叠加；仅 onroad（车辆启动）时可用
 *    - 需先开启 WebrtcStreamEnabled 参数（本页可一键开启，重启后保持）
 */
enum class VideoMode(val title: String, val desc: String) {
    SCREEN("屏幕预览", "和 C3 屏幕一致（含 HUD），offroad 也可用"),
    CAMERA("摄像头流", "硬件编码高清路况，省性能，仅行车时可用")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val videoRepo = remember { VideoStreamRepository(sshManager) }
    val c3Ip = remember { sshManager.connectedHost }

    var mode by remember { mutableStateOf(VideoMode.SCREEN) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var retryKey by remember { mutableIntStateOf(0) }

    // WebRTC 模式下 C3 端开关状态：null=未知/处理中
    var webrtcEnabling by remember { mutableStateOf(false) }

    // 切到摄像头流时自动开启 C3 端 WebrtcStreamEnabled；切回屏幕预览时关闭以省电
    LaunchedEffect(mode) {
        if (mode == VideoMode.CAMERA) {
            webrtcEnabling = true
            videoRepo.enableWebrtcStream()
            webrtcEnabling = false
        } else {
            videoRepo.disableWebrtcStream()
        }
    }

    // 离开视频页面时关闭摄像头流，避免持续占用硬件编码/耗电。
    // 用不随本组件取消的 scope 发送关闭命令（rememberCoroutineScope 会在 onDispose 时被取消）。
    DisposableEffect(Unit) {
        onDispose {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { videoRepo.disableWebrtcStream() }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // ===== 查看方式选择器 =====
        ViewModeSelector(
            selected = mode,
            onSelect = {
                if (it != mode) {
                    mode = it
                    error = null
                    isLoading = true
                    retryKey++
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        Box(
            modifier = Modifier.fillMaxSize(),
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
                    // 根据模式生成 WebView 内容
                    val urlOrNull: String? = if (mode == VideoMode.SCREEN) {
                        "http://$c3Ip:8083/"
                    } else null
                    val htmlOrNull: String? = if (mode == VideoMode.CAMERA) {
                        WebrtcHtml.build(host = c3Ip, port = 5001, camera = "road")
                    } else null

                    VideoCard(
                        retryKey = retryKey,
                        mode = mode,
                        url = urlOrNull,
                        html = htmlOrNull,
                        isLoading = isLoading,
                        webrtcEnabling = webrtcEnabling,
                        c3Ip = c3Ip,
                        onLoadingChange = { isLoading = it },
                        onError = { error = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewModeSelector(
    selected: VideoMode,
    onSelect: (VideoMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Slate100)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        VideoMode.entries.forEach { m ->
            val isSel = m == selected
            Surface(
                shape = RoundedCornerShape(9.dp),
                color = if (isSel) Teal500 else Color.Transparent,
                modifier = Modifier
                    .weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(m) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        m.title,
                        color = if (isSel) Color.White else Slate700,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        m.desc,
                        color = if (isSel) Color(0xFFCCFBF1) else Slate400,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun VideoCard(
    retryKey: Int,
    mode: VideoMode,
    url: String?,
    html: String?,
    isLoading: Boolean,
    webrtcEnabling: Boolean,
    c3Ip: String?,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 4.dp,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1A1A2E))
            ) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.builtInZoomControls = false
                            settings.setSupportZoom(false)
                            setBackgroundColor(0x00000000)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                    onLoadingChange(true)
                                }
                                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                    onLoadingChange(false)
                                }
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    webError: android.webkit.WebResourceError?
                                ) {
                                    if (request?.isForMainFrame == true && mode == VideoMode.SCREEN) {
                                        onError("无法连接到 C3 ($c3Ip:8083)")
                                    }
                                }
                            }
                        }
                    },
                    update = { webView ->
                        // 仅当 mode/retryKey 组合变化时才重新加载，避免重组时无限重载
                        val loadKey = "$mode:$retryKey"
                        if (webView.tag != loadKey) {
                            webView.tag = loadKey
                            when (mode) {
                                VideoMode.SCREEN -> url?.let { webView.loadUrl(it) }
                                VideoMode.CAMERA -> html?.let {
                                    webView.loadDataWithBaseURL(
                                        "http://$c3Ip/",
                                        it,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isLoading || webrtcEnabling) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Teal500,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                when {
                                    webrtcEnabling -> "正在开启 C3 摄像头流..."
                                    mode == VideoMode.SCREEN -> "正在连接 C3 屏幕画面..."
                                    else -> "正在连接摄像头流..."
                                },
                                color = Slate400,
                                fontSize = 13.sp
                            )
                            c3Ip?.let { ip ->
                                Spacer(Modifier.height(4.dp))
                                Text(ip, color = Slate600, fontSize = 12.sp)
                            }
                        }
                    }
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
        Text("视频预览", color = Slate900, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
