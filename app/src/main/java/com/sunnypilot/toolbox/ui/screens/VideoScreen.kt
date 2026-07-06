package com.sunnypilot.toolbox.ui.screens

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    var retryKey by remember { mutableIntStateOf(0) }
    val c3Ip = remember { sshManager.connectedHost }

    LaunchedEffect(retryKey) {
        error = null
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
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
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF2A2A2A)
                    ) {
                        Text(
                            text = error!!,
                            color = Color(0xFFFF6B6B),
                            fontSize = 14.sp,
                            modifier = Modifier.padding(16.dp)
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
                            setBackgroundColor(android.graphics.Color.BLACK)
                            webViewClient = WebViewClient()
                            loadUrl(streamUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Teal400)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "正在连接 C3 视频流...",
                        color = Slate400,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
