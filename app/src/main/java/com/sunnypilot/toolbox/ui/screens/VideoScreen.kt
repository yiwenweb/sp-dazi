package com.sunnypilot.toolbox.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.sunnypilot.toolbox.ui.theme.*

/**
 * 视频预览 - 显示 C3 实时屏幕画面
 *
 * C3 端 Qt UI 内置了 ScreenStreamer（端口 8083），
 * 本页面通过 WebView 连接并显示实时画面。
 * 无需通过 SSH 启动任何额外服务。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var retryKey by remember { mutableIntStateOf(0) }
    val c3Ip = remember { sshManager.connectedHost }

    LaunchedEffect(retryKey) {
        error = null
        isLoading = true
        try {
            val ip = c3Ip
            if (ip.isNullOrBlank()) {
                error = "无法获取 C3 设备 IP 地址\n请确认已通过 SSH 连接到 C3"
                return@LaunchedEffect
            }
            streamUrl = "http://${ip}:8083/"
        } catch (e: Exception) {
            error = "启动视频预览失败: ${e.message}"
        }
    }

    // 卡片式布局：浅色背景 + 居中视频卡片 + 白边围绕
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            error != null -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Text(
                        "视频预览",
                        color = Slate900,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Slate100
                    ) {
                        Text(
                            text = error!!,
                            color = Color(0xFFDC2626),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { retryKey++ },
                        colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                    ) {
                        Text("重试", color = Color.White)
                    }
                }
            }

            streamUrl != null -> {
                val url = streamUrl!!
                // 视频卡片容器
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
                        // 视频内容区（白色边框内）
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
                                        settings.builtInZoomControls = false
                                        settings.setSupportZoom(false)
                                        setBackgroundColor(0x00000000)
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                                                isLoading = true
                                            }
                                            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                                isLoading = false
                                            }
                                            override fun onReceivedError(
                                                view: WebView?,
                                                request: WebResourceRequest?,
                                                webError: android.webkit.WebResourceError?
                                            ) {
                                                if (request?.isForMainFrame == true) {
                                                    error = "无法连接到 C3 ($url)"
                                                }
                                            }
                                        }
                                        loadUrl(url)
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // 加载提示
                            if (isLoading) {
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
                                            "正在连接 C3 实时画面...",
                                            color = Slate400,
                                            fontSize = 13.sp
                                        )
                                        c3Ip?.let { ip ->
                                            Spacer(Modifier.height(4.dp))
                                            Text(
                                                ip,
                                                color = Slate600,
                                                fontSize = 12.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Teal500,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("正在准备视频流...", color = Slate400, fontSize = 13.sp)
                }
            }
        }
    }
}
