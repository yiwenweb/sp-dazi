package com.sunnypilot.toolbox.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.H264StreamRecorder
import com.sunnypilot.toolbox.data.repository.MjpegRecorder
import com.sunnypilot.toolbox.data.repository.MjpegStreamClient
import com.sunnypilot.toolbox.data.repository.SuperVideoClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 视频预览页（C3 屏幕投流 + 反向触摸控制 + 录制）。
 *
 *  - 顶部横条：录制按钮 + 流模式切换 + 帧率 + 分辨率 + 连接状态
 *  - 画面区：**默认走 H264 硬解**（30fps，msm_vidc 硬编 → MediaCodec 硬解，
 *    零编码开销）；带圆角边框、四周留边居中，保持流的宽高比不变形
 *  - 触摸回控：两种模式一致，按 contain 映射回 C3 横屏逻辑坐标（0..2159 / 0..1079）
 *  - 录制：
 *      - H264 模式：[H264StreamRecorder] 把收到的 Annex-B 码流**直接封装成 MP4**
 *        （不做二次编码，零画质损失、零额外耗电）
 *      - MJPEG 模式：[MjpegRecorder] 把 JPEG 帧重新硬编成 H.264 → MP4
 */
@Composable
fun VideoPreviewScreen(
  sshManager: SshManager,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val mainHandler = remember { Handler(Looper.getMainLooper()) }
  val host = remember { sshManager.connectedHost }

  var fps by remember { mutableIntStateOf(0) }
  var imgW by remember { mutableIntStateOf(0) }
  var imgH by remember { mutableIntStateOf(0) }
  var connected by remember { mutableStateOf(false) }
  var recording by remember { mutableStateOf(false) }
  var recordSecs by remember { mutableIntStateOf(0) }
  var viewW by remember { mutableIntStateOf(0) }
  var viewH by remember { mutableIntStateOf(0) }

  // 默认直接进 H264 硬解模式（用户主用路径）
  var h264Mode by remember { mutableStateOf(true) }
  var h264Connected by remember { mutableStateOf(false) }
  var h264Fps by remember { mutableIntStateOf(0) }
  var h264W by remember { mutableIntStateOf(0) }
  var h264H by remember { mutableIntStateOf(0) }
  val h264ClientRef = remember { mutableStateOf<SuperVideoClient?>(null) }
  val textureViewRef = remember { mutableStateOf<TextureView?>(null) }

  val imageViewRef = remember { mutableStateOf<ImageView?>(null) }
  val recorder = remember { MjpegRecorder(context) }
  val h264Recorder = remember { H264StreamRecorder(context) }

  // 拉流客户端：一次解码，同时供显示、帧率统计、录制（仅 MJPEG 模式）
  val client = remember(host, h264Mode) {
    if (host.isNullOrBlank() || h264Mode) null
    else MjpegStreamClient(
      host = host,
      onFrame = { jpeg, ptsUs ->
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@MjpegStreamClient
        if (recorder.isRecording) recorder.encodeBitmap(bmp, ptsUs)
        mainHandler.post {
          val iv = imageViewRef.value ?: run { bmp.recycle(); return@post }
          val old = (iv.drawable as? BitmapDrawable)?.bitmap
          iv.setImageBitmap(bmp)
          if (old != null && old !== bmp && !old.isRecycled) old.recycle()
        }
      },
      onFps = { fps = it },
      onSize = { w, h -> imgW = w; imgH = h },
      onStatus = { connected = it }
    )
  }

  DisposableEffect(client) {
    client?.start()
    onDispose { client?.stop() }
  }

  // H264 客户端生命周期：模式开启即拉流，Surface 由 TextureView 提供。
  //
  // 注意 Compose 的执行顺序：AndroidView 的 factory 可能先于本 DisposableEffect
  // 运行，也可能后于（取决于重组时机）。因此不能在任何一侧"一次性"绑定 Surface：
  //   - 若 TextureView 先就绪，onSurfaceTextureAvailable 里 h264ClientRef 还是 null
  //   - 若 client 先创建，textureViewRef 还是 null
  // 两条路径都必须能补绑定。这里用一个单调递增的 surfaceGen 状态 + LaunchedEffect
  // 做收敛，保证任一顺序下 Surface 最终都会送达 client。
  var surfaceGen by remember { mutableIntStateOf(0) }

  DisposableEffect(host, h264Mode) {
    if (h264Mode && !host.isNullOrBlank()) {
      val c = SuperVideoClient(
        host = host,
        onFps = { h264Fps = it },
        onStatus = { h264Connected = it },
        onResolution = { w, h -> h264W = w; h264H = h }
      )
      // 挂上直存录制器：录制时给 client 用，不录制时它只是 idle
      c.recorder = h264Recorder
      h264ClientRef.value = c
      // 兜底：若两条 Compose 绑定路径都错过，worker 会自己向 TextureView 取 Surface
      c.setFallbackSurfaceProvider {
        val tv = textureViewRef.value
        if (tv != null && tv.isAvailable && tv.surfaceTexture != null) {
          Surface(tv.surfaceTexture)
        } else null
      }
      c.start()
    }
    onDispose {
      h264ClientRef.value?.stop()
      h264ClientRef.value = null
    }
  }

  // 双向收敛：client 就绪 或 TextureView 就绪/换代 时，补一次 Surface 绑定。
  // key 同时含 h264Mode 与 surfaceGen，任一变化都会重跑。
  LaunchedEffect(h264Mode, surfaceGen, h264ClientRef.value) {
    val c = h264ClientRef.value ?: return@LaunchedEffect
    val tv = textureViewRef.value ?: return@LaunchedEffect
    if (h264Mode && tv.isAvailable && tv.surfaceTexture != null) {
      c.setSurface(Surface(tv.surfaceTexture))
    }
  }

  // 录制计时
  LaunchedEffect(recording) {
    recordSecs = 0
    while (recording) {
      kotlinx.coroutines.delay(1000)
      recordSecs++
    }
  }

  // 触摸映射用的「源画面尺寸」：
  // imgW/imgH 只在 MJPEG 模式由 onSize 赋值；H264 模式下 MjpegStreamClient 为 null，
  // 若沿用 imgW/imgH 会恒为 0 → map() 直接返回 (0,0) → 所有触摸都注入到 C3 左上角
  //（真机实测：C3 端只收到 x=0 y=0）。因此 H264 模式必须用解码器上报的分辨率；
  // 两种模式都做兜底，保证宽高比与映射永不为 0。
  val effW = if (h264Mode) (if (h264W > 0) h264W else H264_STREAM_WIDTH)
             else (if (imgW > 0) imgW else H264_STREAM_WIDTH)
  val effH = if (h264Mode) (if (h264H > 0) h264H else H264_STREAM_HEIGHT)
             else (if (imgH > 0) imgH else H264_STREAM_HEIGHT)

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF05080B))
  ) {
    // ===== 顶部横条 =====
    Surface(color = Color(0xFF111827), tonalElevation = 0.dp) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(54.dp)
          .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        // 录制按钮（两种模式都支持，底层实现不同）
        RecordButton(
          recording = recording,
          seconds = recordSecs,
          enabled = if (h264Mode) h264Connected else connected,
          onClick = {
            if (!recording) {
              if (h264Mode) {
                // H264：把收到的码流直接封成 MP4（等首个 IDR 才开始写）
                val w = if (h264W > 0) h264W else H264_STREAM_WIDTH
                val h = if (h264H > 0) h264H else H264_STREAM_HEIGHT
                val p = h264ClientRef.value?.startRecording(w, h)
                if (p != null) {
                  recording = true
                } else {
                  toast(context, "录制启动失败：参数集未就绪，稍候一两秒再试")
                }
              } else {
                if (imgW > 0 && imgH > 0) {
                  runCatching { recorder.start(imgW, imgH) }
                    .onSuccess { recording = true }
                    .onFailure { toast(context, "录制启动失败: ${it.message}") }
                } else {
                  toast(context, "画面尚未就绪，稍候再录")
                }
              }
            } else {
              val saved = if (h264Mode) h264ClientRef.value?.stopRecording() else recorder.stop()
              recording = false
              if (saved != null) toast(context, "视频已保存：$saved")
              else toast(context, "录制失败：未等到关键帧，未生成文件")
            }
          }
        )

        Divider(
          modifier = Modifier
            .height(24.dp)
            .width(1.dp),
          color = Color.White.copy(alpha = 0.12f)
        )

        // 流模式切换：H264 硬解（默认）/ MJPEG
        ModeChip(
          h264Mode = h264Mode,
          onClick = {
            if (recording) {
              if (h264Mode) h264ClientRef.value?.stopRecording() else recorder.stop()
              recording = false
            }
            h264Mode = !h264Mode
          }
        )

        // 帧率
        InfoChip(
          icon = Icons.Default.Videocam,
          label = "帧率",
          value = if (h264Mode) (if (h264Connected) "$h264Fps FPS" else "—") else (if (connected) "$fps FPS" else "—")
        )
        // 分辨率
        InfoChip(
          icon = Icons.Default.HighQuality,
          label = "分辨率",
          value = "$effW × $effH"
        )

        Spacer(modifier = Modifier.weight(1f))

        // 连接状态
        val effConnected = if (h264Mode) h264Connected else connected
        val dotColor = if (effConnected) Color(0xFF34D399) else Color(0xFFFBBF24)
        val statusText = when {
          host.isNullOrBlank() -> "未连接 C3"
          effConnected -> "已连接 $host"
          else -> "连接中…"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .background(dotColor, CircleShape)
          )
          Spacer(Modifier.width(6.dp))
          Text(statusText, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        }
      }
    }

    // ===== 画面区：留边 + 圆角边框 + 保持流宽高比，且可触摸回控 C3 =====
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(Color(0xFF05080B))
        .padding(horizontal = 20.dp, vertical = 10.dp),
      contentAlignment = Alignment.Center
    ) {
      Box(
        modifier = Modifier
          .fillMaxHeight()
          .aspectRatio(effW.toFloat() / effH.toFloat())
          .clip(RoundedCornerShape(12.dp))
          .border(2.dp, Color(0xFF2A3644), RoundedCornerShape(12.dp))
          .background(Color.Black)
          .onSizeChanged { viewW = it.width; viewH = it.height }
          .pointerInput(host, effW, effH, viewW, viewH) {
            if (host.isNullOrBlank()) return@pointerInput
            // 触摸坐标：容器像素 -> contain 内容区 -> C3 横屏逻辑坐标 0..2159 / 0..1079
            fun map(px: Float, py: Float): Pair<Int, Int> {
              if (effW <= 0 || effH <= 0 || viewW <= 0 || viewH <= 0) return 0 to 0
              val sc = min(viewW.toFloat() / effW, viewH.toFloat() / effH)
              val dw = effW * sc
              val dh = effH * sc
              val dx = (viewW - dw) / 2f
              val dy = (viewH - dh) / 2f
              val fx = ((px - dx) / dw).coerceIn(0f, 1f)
              val fy = ((py - dy) / dh).coerceIn(0f, 1f)
              return (fx * C3_MAX_X).roundToInt() to (fy * C3_MAX_Y).roundToInt()
            }
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              var last = map(down.position.x, down.position.y)
              sendTouch(host, last.first, last.second, "down", scope)
              while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: break
                if (change.changedToUpIgnoreConsumed()) {
                  sendTouch(host, 0, 0, "up", scope)
                  break
                }
                val p = map(change.position.x, change.position.y)
                if (abs(p.first - last.first) + abs(p.second - last.second) >= 2) {
                  sendTouch(host, p.first, p.second, "move", scope)
                  last = p
                }
              }
            }
          }
      ) {
        if (h264Mode) {
          // ===== H264 硬解模式：TextureView + MediaCodec Surface 渲染 =====
          // 注意：SurfaceTexture 默认缓冲尺寸为 0x0，若不在 attach 时显式
          // setDefaultBufferSize(1280, 640)，MediaCodec 会渲染到 0x0 → 全黑。
          AndroidView(
            factory = { ctx ->
              TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                  override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    st.setDefaultBufferSize(H264_STREAM_WIDTH, H264_STREAM_HEIGHT)
                    h264ClientRef.value?.setSurface(Surface(st))
                    surfaceGen++          // 通知收敛 effect 补绑定
                  }
                  override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                    st.setDefaultBufferSize(H264_STREAM_WIDTH, H264_STREAM_HEIGHT)
                    h264ClientRef.value?.setSurface(Surface(st))
                    surfaceGen++
                  }
                  override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    h264ClientRef.value?.setSurface(null)
                    return true
                  }
                  override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                textureViewRef.value = this
              }
            },
            modifier = Modifier.fillMaxSize()
          )
          if (!h264Connected) {
            Column(
              modifier = Modifier.align(Alignment.Center),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              CircularProgressIndicator(color = Color(0xFF14B8A6), strokeWidth = 2.dp, modifier = Modifier.size(34.dp))
              Spacer(Modifier.height(10.dp))
              Text(
                if (host.isNullOrBlank()) "未连接到 C3，请先在连接中心建立连接"
                else "正在连接 C3 硬编码流（:8082）…",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp
              )
            }
          }
        } else {
          // ===== MJPEG 模式：ImageView 逐帧显示 =====
          AndroidView(
            factory = { ctx ->
              ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(android.graphics.Color.BLACK)
                imageViewRef.value = this
              }
            },
            modifier = Modifier.fillMaxSize()
          )
          if (!connected || imgW == 0) {
            Column(
              modifier = Modifier.align(Alignment.Center),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              CircularProgressIndicator(color = Color(0xFF14B8A6), strokeWidth = 2.dp, modifier = Modifier.size(34.dp))
              Spacer(Modifier.height(10.dp))
              Text(
                if (host.isNullOrBlank()) "未连接到 C3，请先在连接中心建立连接" else "正在获取 C3 画面…",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp
              )
            }
          }
        }
      }
    }
  }
}

private fun toast(context: android.content.Context, msg: String) {
  android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
}

@Composable
private fun RecordButton(
  recording: Boolean,
  seconds: Int,
  enabled: Boolean,
  onClick: () -> Unit
) {
  val mm = seconds / 60
  val ss = seconds % 60
  val timeText = "%02d:%02d".format(mm, ss)
  Button(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(10.dp),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = if (recording) Color.White else Color(0xFFDC2626),
      contentColor = if (recording) Color(0xFFDC2626) else Color.White
    )
  ) {
    Icon(
      imageVector = if (recording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
      contentDescription = null,
      modifier = Modifier.size(16.dp)
    )
    Spacer(Modifier.width(6.dp))
    Text(
      text = if (recording) "停止 $timeText" else "录制视频",
      fontSize = 13.sp
    )
  }
}

@Composable
private fun ModeChip(
  h264Mode: Boolean,
  onClick: () -> Unit
) {
  Surface(
    shape = RoundedCornerShape(8.dp),
    color = if (h264Mode) Color(0xFF14B8A6) else Color.White.copy(alpha = 0.10f),
    onClick = onClick
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        if (h264Mode) "H264 硬解 30fps" else "MJPEG 8fps",
        color = if (h264Mode) Color.White else Color.White.copy(alpha = 0.85f),
        fontSize = 12.sp
      )
    }
  }
}

@Composable
private fun InfoChip(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  value: String
) {
  Row(
    modifier = Modifier
      .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
      .padding(horizontal = 10.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(icon, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(5.dp))
    Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
    Spacer(Modifier.width(5.dp))
    Text(value, color = Color.White, fontSize = 12.sp)
  }
}

/** 通过 HTTP 把触摸事件发给 C3 stream_server 的 /input（IO 线程，失败静默）。 */
private fun sendTouch(
  host: String,
  x: Int,
  y: Int,
  type: String,
  scope: kotlinx.coroutines.CoroutineScope
) {
  scope.launch(Dispatchers.IO) {
    var conn: HttpURLConnection? = null
    try {
      conn = (URL("http://$host:8081/input").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 1500
        readTimeout = 1500
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
      }
      val body = """{"x":$x,"y":$y,"type":"$type"}"""
      conn.outputStream.use { it.write(body.toByteArray()) }
      conn.inputStream.use { it.readBytes() }
    } catch (_: Exception) {
      // 触摸丢包不影响观看，静默
    } finally {
      conn?.disconnect()
    }
  }
}

private const val C3_MAX_X = 2159f
private const val C3_MAX_Y = 1079f

/** C3 super video 硬编码输出的 Negotiated 分辨率（与 supervideo.cc STREAM_WIDTH/HEIGHT 一致） */
private const val H264_STREAM_WIDTH = 1280
private const val H264_STREAM_HEIGHT = 640
