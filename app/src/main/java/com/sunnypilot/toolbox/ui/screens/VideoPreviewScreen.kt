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
import com.sunnypilot.toolbox.data.repository.MjpegRecorder
import com.sunnypilot.toolbox.data.repository.MjpegStreamClient
import com.sunnypilot.toolbox.data.repository.SuperVideoClient
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 视频预览页（原「超级视频」迁移至此，替换旧的 H264 摄像头预览）。
 *
 *  - 顶部横条：录制视频按钮 + 实时帧率 + 分辨率 + 连接状态 + 流模式切换
 *  - 下方：两种模式
 *      - MJPEG（默认）：拉 C3 :8081 MJPEG UI 画面（8fps，PIL 软编码），支持录制
 *      - H264 硬解：拉 C3 :8082 msm_vidc 硬编码 H.264（30fps，零 CPU 编码开销），
 *        MediaCodec 硬解渲染到 TextureView；触摸回控两种模式一致（:8081/input）
 *  - 触摸按 contain 映射回 C3（0..2159 / 0..1079）
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

  // H264 硬解模式状态
  var h264Mode by remember { mutableStateOf(false) }
  var h264Connected by remember { mutableStateOf(false) }
  var h264Fps by remember { mutableIntStateOf(0) }
  var h264W by remember { mutableIntStateOf(0) }
  var h264H by remember { mutableIntStateOf(0) }
  val h264ClientRef = remember { mutableStateOf<SuperVideoClient?>(null) }
  val textureViewRef = remember { mutableStateOf<TextureView?>(null) }

  val imageViewRef = remember { mutableStateOf<ImageView?>(null) }
  val recorder = remember { MjpegRecorder(context) }

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

  // H264 客户端生命周期：模式开启即拉流，Surface 由 TextureView 提供
  DisposableEffect(host, h264Mode) {
    if (h264Mode && !host.isNullOrBlank()) {
      val c = SuperVideoClient(
        host = host,
        onFps = { h264Fps = it },
        onStatus = { h264Connected = it },
        onResolution = { w, h -> h264W = w; h264H = h },
        onError = { _ -> }
      )
      // 先公开引用再 start：worker 起来后 TextureView 回调必须能拿到它
      h264ClientRef.value = c
      textureViewRef.value?.let { tv ->
        if (tv.isAvailable) c.setSurface(Surface(tv.surfaceTexture))
      }
      c.start()
    }
    onDispose {
      h264ClientRef.value?.stop()
      h264ClientRef.value = null
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

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0B0F14))
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
        // 录制视频按钮（仅 MJPEG 模式支持录制）
        if (!h264Mode) {
          RecordButton(
            recording = recording,
            seconds = recordSecs,
            onClick = {
              if (!recording) {
                if (imgW > 0 && imgH > 0) {
                  runCatching { recorder.start(imgW, imgH) }
                    .onSuccess { recording = true }
                    .onFailure { android.widget.Toast.makeText(context, "录制启动失败: ${it.message}", android.widget.Toast.LENGTH_SHORT).show() }
                } else {
                  android.widget.Toast.makeText(context, "画面尚未就绪，稍候再录", android.widget.Toast.LENGTH_SHORT).show()
                }
              } else {
                val path = recorder.stop()
                recording = false
                val msg = if (path != null) "视频已保存：$path" else "录制失败"
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
              }
            }
          )
        }

        Divider(
          modifier = Modifier
            .height(24.dp)
            .width(1.dp),
          color = Color.White.copy(alpha = 0.12f)
        )

        // 流模式切换：MJPEG（8fps 可录制）/ H264 硬解（30fps）
        ModeChip(
          h264Mode = h264Mode,
          onClick = {
            if (recording) {
              recorder.stop()
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
          value = if (h264Mode) (if (h264W > 0) "$h264W × $h264H" else "1280 × 640") else (if (imgW > 0) "$imgW × $imgH" else "—")
        )

        Spacer(modifier = Modifier.weight(1f))

        // 连接状态
        val effConnected = if (h264Mode) h264Connected else connected
        val dotColor = if (effConnected) Green500 else Amber500
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

    // ===== 视频画面区（可触摸回控 C3）=====
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(Color.Black)
        .onSizeChanged { viewW = it.width; viewH = it.height }
        .pointerInput(host, imgW, imgH, viewW, viewH) {
          if (host.isNullOrBlank()) return@pointerInput
          // 触摸坐标：容器像素 -> contain 内容区 -> C3 横屏逻辑坐标 0..2159 / 0..1079
          fun map(px: Float, py: Float): Pair<Int, Int> {
            if (imgW <= 0 || imgH <= 0 || viewW <= 0 || viewH <= 0) return 0 to 0
            val sc = min(viewW.toFloat() / imgW, viewH.toFloat() / imgH)
            val dw = imgW * sc
            val dh = imgH * sc
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
        AndroidView(
          factory = { ctx ->
            TextureView(ctx).apply {
              surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                  h264ClientRef.value?.setSurface(Surface(st))
                }
                override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
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
            CircularProgressIndicator(color = Teal500, strokeWidth = 2.dp, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text(
              if (host.isNullOrBlank()) "未连接到 C3，请先在连接中心建立连接"
              else "正在连接 C3 硬编码流（:8082），请确认 C3 视觉页已开启 Super Video…",
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
            CircularProgressIndicator(color = Teal500, strokeWidth = 2.dp, modifier = Modifier.size(34.dp))
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

@Composable
private fun RecordButton(
  recording: Boolean,
  seconds: Int,
  onClick: () -> Unit
) {
  val mm = seconds / 60
  val ss = seconds % 60
  val timeText = "%02d:%02d".format(mm, ss)
  Button(
    onClick = onClick,
    shape = RoundedCornerShape(10.dp),
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = if (recording) Color.White else Red600,
      contentColor = if (recording) Red600 else Color.White
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
    color = if (h264Mode) Teal500 else Color.White.copy(alpha = 0.10f),
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
