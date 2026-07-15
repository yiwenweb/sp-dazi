package com.sunnypilot.toolbox.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sunnypilot.toolbox.ui.theme.*

/**
 * 手机投屏 — 接收手机 RTSP/HTTP 视频流, ExoPlayer 原生硬解播放。
 *
 * 手机端: 安装任意投屏 App (如 Screen Stream, RTSP Camera Server),
 *         启动后获取推流地址 (例: rtsp://192.168.1.5:8554/stream)。
 * 车机端: 输入地址 → 点击播放 → ExoPlayer 直连, 延迟低至 50-100ms。
 *
 * 支持: RTSP, HTTP, HLS, MP4 等 ExoPlayer 原生协议。
 */
@OptIn(UnstableApi::class)
@Composable
fun ScreenMirrorScreen(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current

    var urlInput by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    // 生命周期: 离开页面时释放播放器
    DisposableEffect(Unit) {
        onDispose {
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    // 监听播放器错误
    LaunchedEffect(exoPlayer) {
        exoPlayer?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    errorMsg = null
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                errorMsg = "播放失败: ${error.localizedMessage ?: "无法连接"}"
                isPlaying = false
            }
        })
    }

    fun startPlay(url: String) {
        focusManager.clearFocus()
        errorMsg = null
        val finalUrl = url.trim()
        if (finalUrl.isBlank()) {
            errorMsg = "请输入投屏地址"
            return
        }
        try {
            exoPlayer?.release()
            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(finalUrl))
                prepare()
                playWhenReady = true
            }
            exoPlayer = player
            isPlaying = true
        } catch (e: Exception) {
            errorMsg = "启动失败: ${e.message}"
            isPlaying = false
        }
    }

    fun stopPlay() {
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        isPlaying = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ===== 标题 =====
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Teal50,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Cast,
                    contentDescription = null,
                    tint = Teal500,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("手机投屏", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Slate900)
                    Text(
                        "接收手机 RTSP/HTTP 视频流，ExoPlayer 硬解播放",
                        fontSize = 12.sp, color = Slate500
                    )
                }
            }
        }

        // ===== 地址输入 + 播放按钮 =====
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color.White,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = {
                        Text("rtsp://手机IP:端口/stream", fontSize = 13.sp, color = Slate400)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = { startPlay(urlInput) }
                    ),
                    leadingIcon = {
                        Icon(Icons.Default.Link, null, tint = Slate400, modifier = Modifier.size(20.dp))
                    },
                    enabled = !isPlaying,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Teal500,
                        unfocusedBorderColor = Slate200
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.width(8.dp))
                if (isPlaying) {
                    Button(
                        onClick = { stopPlay() },
                        colors = ButtonDefaults.buttonColors(containerColor = Red500),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("停止", fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = { startPlay(urlInput) },
                        colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("播放", fontSize = 13.sp)
                    }
                }
            }
        }

        // ===== 错误提示 =====
        if (errorMsg != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Red50
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = Red500, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(errorMsg!!, fontSize = 13.sp, color = Red500)
                }
            }
        }

        // ===== 视频播放区 =====
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1A1A2E),
            shadowElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying && exoPlayer != null) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                setBackgroundColor(0xFF1A1A2E.toInt())

                                val playerView = PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = true
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                    layoutParams = FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.MATCH_PARENT
                                    )
                                }
                                addView(playerView)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Default.CastConnected,
                            contentDescription = null,
                            tint = Slate600,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "输入手机投屏地址后点击播放",
                            fontSize = 14.sp, color = Slate400
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "支持 RTSP / HTTP / HLS 流",
                            fontSize = 12.sp, color = Slate500
                        )
                    }
                }
            }
        }

        // ===== 使用说明 =====
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Slate50,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("使用方法", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Slate700)
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. 手机安装投屏 App（如 Screen Stream、RTSP Camera Server）\n" +
                    "2. 开启手机热点，车机连接该 WiFi\n" +
                    "3. 手机启动推流，获取 RTSP 地址\n" +
                    "4. 把地址填入上方输入框，点击播放",
                    fontSize = 12.sp, color = Slate500, lineHeight = 18.sp
                )
            }
        }
    }
}
