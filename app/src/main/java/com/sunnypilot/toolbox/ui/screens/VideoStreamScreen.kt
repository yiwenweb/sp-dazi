package com.sunnypilot.toolbox.ui.screens

import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.OverlayDataClient
import com.sunnypilot.toolbox.data.repository.OverlayMsg
import com.sunnypilot.toolbox.data.repository.StreamCtrlClient
import com.sunnypilot.toolbox.data.repository.SuperVideoClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 视频流页：C3 摄像头画面 + 模型叠加（车道线 / 行车轨迹 / 前车标记）。
 *
 * 数据来源（C3 侧 `streamfwd.py`）：
 *   :8083  wideRoad 硬件 H.264（1928×1208，标准 Annex-B，每帧自带 SPS/PPS）
 *   :8084  JSON Lines —— **已投影好的 2D 像素坐标**（坐标系 = 视频帧）
 *
 * 对齐原理：C3 侧用与 Qt UI 完全相同的矩阵链
 *   `car_space_transform = video_transform × K × view_from_wide_calib`
 * 把 modelV2 的三维点算成视频帧像素坐标，所以 App 侧**不需要任何投影运算**，
 * 只要把 1928×1208 等比缩放到画面控件尺寸，就能与 C3 屏幕上看到的完全重合。
 *
 * 各元素的画法（颜色/宽度/透明度）也照抄了 Qt UI：
 *   车道线  白色，alpha = clamp(prob, 0, 0.7)，半宽 = 0.025 × prob（C3 侧已按此生成多边形）
 *   道路边缘 红色，alpha = 1 - std
 *   行车轨迹 青绿渐变（0/0.5/1 三档，alpha 0.4 → 0），从画面底部向上淡出
 *   前车标记 黄色发光三角 + 红色内三角（chevron），尺寸随距离变化
 */
@Composable
fun VideoStreamScreen(
  sshManager: SshManager,
  modifier: Modifier = Modifier
) {
  val host = remember { sshManager.connectedHost }

  var videoConnected by remember { mutableStateOf(false) }
  var fps by remember { mutableIntStateOf(0) }
  var streamW by remember { mutableIntStateOf(1928) }
  var streamH by remember { mutableIntStateOf(1208) }
  var dataConnected by remember { mutableStateOf(false) }
  var overlay by remember { mutableStateOf<OverlayMsg?>(null) }
  var lastDataAt by remember { mutableLongStateOf(0L) }
  var tick by remember { mutableIntStateOf(0) }          // 用于刷新"数据新鲜度"显示

  val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
  val videoClientRef = remember { mutableStateOf<SuperVideoClient?>(null) }
  var surfaceGen by remember { mutableIntStateOf(0) }

  // ── C3 侧服务生命周期 / 控制状态 ──
  var svcState by remember { mutableStateOf("启动中…") }   // 启动中… / 已启动 / 启动失败
  var camMode by remember { mutableStateOf("wide") }       // 以 C3 回报为准
  var recording by remember { mutableStateOf(false) }
  var recNote by remember { mutableStateOf<String?>(null) }
  // 独立 scope：onDispose 之后 Composable 自身的 scope 已被取消，
  // 但"离开页面必须停掉 C3 服务"不能因此丢失，所以用不随页面销毁的 scope。
  val bgScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

  // ── 进入页面：自动让 C3 启动服务（camerad + 模型链 + 转发器）──
  LaunchedEffect(host) {
    if (host.isNullOrBlank()) {
      svcState = "未连接 C3"
      return@LaunchedEffect
    }
    svcState = "启动中…"
    val r = sshManager.executeCommand("bash /data/local/tmp/c3_stream_start.sh")
    svcState = if (r.getOrNull()?.contains("OK") == true) "已启动" else "启动失败"
  }

  // ── 离开页面：立刻停掉 C3 服务，避免继续占用算力 ──
  DisposableEffect(host) {
    onDispose {
      // 正在录制就先落盘，别留下损坏文件
      runCatching {
        videoClientRef.value?.let { if (it.isRecording) it.stopRecording() }
      }
      if (!host.isNullOrBlank()) {
        bgScope.launch {
          runCatching { sshManager.executeCommand("bash /data/local/tmp/c3_stream_stop.sh") }
        }
      }
    }
  }

  // 摄像头状态以 C3 回报为准（避免乐观更新与实况不一致）
  LaunchedEffect(overlay?.cam) {
    overlay?.cam?.takeIf { it.isNotBlank() }?.let { camMode = it }
  }

  // ── 视频客户端 ──
  DisposableEffect(host) {
    if (!host.isNullOrBlank()) {
      val c = SuperVideoClient(
        host = host,
        port = VIDEO_PORT,
        streamWidth = STREAM_W,
        streamHeight = STREAM_H,
        onFps = { fps = it },
        onStatus = { videoConnected = it },
        onResolution = { w, h -> streamW = w; streamH = h }
      )
      videoClientRef.value = c
      c.setFallbackSurfaceProvider {
        val tv = textureViewRef.value
        if (tv != null && tv.isAvailable && tv.surfaceTexture != null) Surface(tv.surfaceTexture) else null
      }
      c.start()
    }
    onDispose {
      videoClientRef.value?.stop()
      videoClientRef.value = null
    }
  }

  // ── 数据客户端 ──
  DisposableEffect(host) {
    if (!host.isNullOrBlank()) {
      val d = OverlayDataClient(
        host = host,
        port = DATA_PORT,
        onFrame = { msg -> overlay = msg; lastDataAt = System.currentTimeMillis() },
        onStatus = { dataConnected = it }
      )
      d.start()
      onDispose { d.stop() }
    } else {
      onDispose { }
    }
  }

  // Surface 收敛绑定（与视频预览页同一套时序处理）
  LaunchedEffect(surfaceGen, videoClientRef.value) {
    val c = videoClientRef.value ?: return@LaunchedEffect
    val tv = textureViewRef.value ?: return@LaunchedEffect
    if (tv.isAvailable && tv.surfaceTexture != null) c.setSurface(Surface(tv.surfaceTexture))
  }

  // 数据新鲜度显示刷新
  LaunchedEffect(Unit) {
    while (true) {
      kotlinx.coroutines.delay(500)
      tick++
    }
  }

  val dataFresh = lastDataAt > 0 && (System.currentTimeMillis() - lastDataAt) < 2000

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF05080B))
  ) {
    // ── 顶部状态条 ──
    Surface(color = Color(0xFF111827), tonalElevation = 0.dp) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(46.dp)
          .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        StatusDot(
          ok = videoConnected,
          text = if (videoConnected) "视频 $fps FPS" else "视频未连接"
        )
        StatusDot(
          ok = dataFresh,
          text = when {
            !dataConnected -> "数据未连接"
            dataFresh -> "叠加数据 ${overlay?.id ?: 0}"
            else -> "叠加数据等待中"
          },
          amberWhenNotOk = true
        )
        // C3 资源占用（数据帧里带过来）
        val cpuTxt = overlay?.let { m ->
          val c = m.cpuUse ?: m.cpu
          if (c != null) " · CPU ${c.toInt()}%" else ""
        } ?: ""
        Text(
          "${streamW} × ${streamH}$cpuTxt",
          color = Color.White.copy(alpha = 0.6f),
          fontSize = 12.sp
        )
        Spacer(Modifier.weight(1f))
        if (overlay?.cal?.st == 1) {
          Text("已标定", color = Color(0xFF34D399), fontSize = 12.sp)
        } else if (overlay != null) {
          Text("标定中", color = Color(0xFFFBBF24), fontSize = 12.sp)
        }
        // C3 服务状态：进入页面自动启动，离开页面自动停止
        Text(
          svcState,
          color = when (svcState) {
            "已启动" -> Color(0xFF34D399)
            "启动失败" -> Color(0xFFF87171)
            else -> Color(0xFFFBBF24)
          },
          fontSize = 12.sp
        )
      }
    }

    // ── 画面区：左侧独立控制栏 + 视频/叠加（按钮不与视频内容重叠）──
    Row(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(Color(0xFF05080B))
        .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
      CtrlRail(
        camMode = camMode,
        recording = recording,
        enabled = !host.isNullOrBlank(),
        note = recNote,
        onCam = { m ->
          if (!host.isNullOrBlank()) {
            bgScope.launch {
              val r = StreamCtrlClient.send(host, m)
              recNote = if (r.isSuccess) ("已切到" + if (m == "wide") "广角" else "普通")
                        else "切换失败"
            }
          }
        },
        onRec = {
          val c = videoClientRef.value
          when {
            c == null -> recNote = "视频未连接"
            c.isRecording -> {
              val path = c.stopRecording()
              recording = false
              recNote = path?.let { "已保存 " + it.substringAfterLast('/') } ?: "停止失败"
            }
            else -> {
              // 注意语义：startRecording 返回**输出文件路径**，null 才是失败
              val path = c.startRecording(streamW, streamH)
              if (path != null) {
                recording = true
                recNote = "录制中…"
              } else {
                recNote = "开始失败（参数集未就绪）"
              }
            }
          }
        }
      )

      // 视频 + 叠加（同一容器保证对齐）
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxHeight(),
        contentAlignment = Alignment.Center
      ) {
      BoxWithConstraints {
        // contain 布局：保持 1928×1208 比例
        val ratio = STREAM_W.toFloat() / STREAM_H.toFloat()
        val avail = maxWidth.value / maxHeight.value
        val dw = if (avail > ratio) (maxHeight * ratio) else maxWidth
        val dh = dw / ratio

        Box(
          modifier = Modifier
            .size(dw, dh)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF2A3644), RoundedCornerShape(12.dp))
            .background(Color.Black)
        ) {
          // 视频层
          AndroidView(
            factory = { ctx ->
              TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                  override fun onSurfaceTextureAvailable(
                    st: android.graphics.SurfaceTexture, w: Int, h: Int
                  ) {
                    st.setDefaultBufferSize(STREAM_W, STREAM_H)
                    videoClientRef.value?.setSurface(Surface(st))
                    surfaceGen++
                  }

                  override fun onSurfaceTextureSizeChanged(
                    st: android.graphics.SurfaceTexture, w: Int, h: Int
                  ) {
                    st.setDefaultBufferSize(STREAM_W, STREAM_H)
                    videoClientRef.value?.setSurface(Surface(st))
                    surfaceGen++
                  }

                  override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                    videoClientRef.value?.setSurface(null)
                    return true
                  }

                  override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                }
                textureViewRef.value = this
              }
            },
            modifier = Modifier.fillMaxSize()
          )

          // 叠加层（数据坐标系 = 视频帧像素，只需等比缩放）
          OverlayLayer(msg = overlay, modifier = Modifier.fillMaxSize())

          // HUD
          HudLayer(msg = overlay, modifier = Modifier.fillMaxSize())

          if (!videoConnected) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
              Text(
                if (host.isNullOrBlank()) "未连接到 C3" else "正在连接视频流（:8083）…",
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
}

/** 车道线 / 道路边缘 / 行车轨迹 / 前车标记的绘制。 */
@Composable
private fun OverlayLayer(msg: OverlayMsg?, modifier: Modifier = Modifier) {
  Canvas(modifier) {
    val m = msg ?: return@Canvas
    val sw = if (m.w > 0) m.w.toFloat() else STREAM_W.toFloat()
    val s = size.width / sw                      // 等比缩放：视频帧像素 → 控件像素

    // ── 车道线（白色，alpha = 概率）──
    m.lines.forEachIndexed { i, poly ->
      if (poly.size < 3) return@forEachIndexed
      val path = Path()
      var started = false
      poly.forEach { pt ->
        if (pt.size >= 2) {
          val x = pt[0] * s
          val y = pt[1] * s
          if (!started) {
            path.moveTo(x, y); started = true
          } else {
            path.lineTo(x, y)
          }
        }
      }
      if (started) {
        path.close()
        val a = m.lAlpha.getOrElse(i) { 0.4f }.coerceIn(0f, 1f)
        drawPath(path, Color.White.copy(alpha = a))
      }
    }

    // ── 道路边缘（红色，alpha = 1 - std）──
    m.edges.forEachIndexed { i, poly ->
      if (poly.size < 3) return@forEachIndexed
      val path = Path()
      var started = false
      poly.forEach { pt ->
        if (pt.size >= 2) {
          val x = pt[0] * s
          val y = pt[1] * s
          if (!started) {
            path.moveTo(x, y); started = true
          } else {
            path.lineTo(x, y)
          }
        }
      }
      if (started) {
        path.close()
        val a = m.eAlpha.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        drawPath(path, Color(0xFFE24B4A).copy(alpha = a))
      }
    }

    // ── 行车轨迹（青绿渐变，从画面底部向上淡出）──
    if (m.track.size >= 3) {
      val path = Path()
      var started = false
      m.track.forEach { pt ->
        if (pt.size >= 2) {
          val x = pt[0] * s
          val y = pt[1] * s
          if (!started) {
            path.moveTo(x, y); started = true
          } else {
            path.lineTo(x, y)
          }
        }
      }
      if (started) {
        path.close()
        drawPath(
          path,
          Brush.verticalGradient(
            colors = listOf(
              Color(0xFF0DF77A).copy(alpha = 0.40f),
              Color(0xFF72FF5C).copy(alpha = 0.35f),
              Color(0xFF72FF5C).copy(alpha = 0.00f)
            ),
            startY = size.height,
            endY = 0f
          )
        )
      }
    }

    // ── 前车标记（黄色发光三角 + 红色内三角，照抄 Qt drawLead）──
    val ld = m.lead
    val lpx = ld?.px
    val lpy = ld?.py
    if (ld != null && ld.p != 0 && lpx != null && lpy != null) {
      val dRel = ld.d
      val vRelMs = ld.rv / 3.6f                     // km/h → m/s（Qt 用 m/s）
      var fillAlpha = 0f
      if (dRel < LEAD_BUFF) {
        fillAlpha = 255f * (1f - dRel / LEAD_BUFF)
        if (vRelMs < 0f) fillAlpha += 255f * (-vRelMs / SPEED_BUFF)
        fillAlpha = min(fillAlpha, 255f)
      }
      val sz = (750f / (dRel / 3f + 30f)).coerceIn(15f, 30f) * 2.35f * s
      val x = lpx * s
      val y = lpy * s
      val cx = x.coerceIn(0f, size.width - sz / 2f)
      val cy = min(y, size.height - sz * 0.6f)
      val gxo = sz / 5f
      val gyo = sz / 10f

      val glow = Path().apply {
        moveTo(cx + sz * 1.35f + gxo, cy + sz + gyo)
        lineTo(cx, cy - gyo)
        lineTo(cx - sz * 1.35f - gxo, cy + sz + gyo)
        close()
      }
      drawPath(glow, Color(218, 202, 37))

      val chevron = Path().apply {
        moveTo(cx + sz * 1.25f, cy + sz)
        lineTo(cx, cy)
        lineTo(cx - sz * 1.25f, cy + sz)
        close()
      }
      drawPath(chevron, Color(201, 34, 49, fillAlpha.toInt().coerceIn(0, 255)))
    }
  }
}

/** 车速 / 前车距离 / C3 状态 / 加速度 等数值叠加。 */
@Composable
private fun HudLayer(msg: OverlayMsg?, modifier: Modifier = Modifier) {
  val m = msg ?: return
  Box(modifier) {
    // 车速（顶部居中）
    val vEgo = m.v
    if (vEgo != null) {
      Row(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .padding(top = 10.dp),
        verticalAlignment = Alignment.Bottom
      ) {
        Text(
          "${(vEgo * 3.6f).toInt()}",
          color = Color.White,
          fontSize = 34.sp,
          fontWeight = FontWeight.Medium,
          fontFamily = FontFamily.SansSerif
        )
        Text(
          " km/h",
          color = Color.White.copy(alpha = 0.75f),
          fontSize = 13.sp,
          modifier = Modifier.padding(bottom = 6.dp)
        )
      }
    }

    // 左上：激活状态
    Column(
      modifier = Modifier
        .align(Alignment.TopStart)
        .padding(10.dp)
    ) {
      val lat = m.lat == 1
      val lon = m.lon == 1
      Text(
        if (m.en == 1) "已激活" else "未激活",
        color = if (m.en == 1) Color(0xFF34D399) else Color(0xFFFFB4B4),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
      )
      Spacer(Modifier.height(2.dp))
      Text(
        buildString {
          append(if (lat) "横向 ✓" else "横向 —")
          append("   ")
          append(if (lon) "纵向 ✓" else "纵向 —")
        },
        color = Color.White.copy(alpha = 0.8f),
        fontSize = 11.sp
      )
    }

    // 右上：C3 状态
    Column(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(10.dp),
      horizontalAlignment = Alignment.End
    ) {
      m.cpu?.let {
        Text("C3 ${it.toInt()}°C", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
      }
      m.bat?.let {
        Text("电量 $it%", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
      }
    }

    // 右侧：前车信息
    val ld = m.lead
    if (ld != null && ld.p != 0) {
      Column(
        modifier = Modifier
          .align(Alignment.CenterEnd)
          .padding(end = 10.dp),
        horizontalAlignment = Alignment.End
      ) {
        Text("前车", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
        Text(
          "${"%.1f".format(ld.d)} m",
          color = Color.White,
          fontSize = 15.sp,
          fontWeight = FontWeight.Medium
        )
        Text(
          "相对 ${"%.1f".format(ld.rv)} km/h",
          color = Color.White.copy(alpha = 0.85f),
          fontSize = 11.sp
        )
      }
    }

    // 底部：转向角 / 扭矩 / 加速度
    Row(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .background(Color(0x99000000))
        .padding(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.SpaceEvenly
    ) {
      HudCell("转向角", m.sa?.let { "%.1f°".format(it) } ?: "—")
      HudCell("扭矩", m.tq?.let { "%.0f".format(it) } ?: "—")
      HudCell("加速度", m.a?.let { "%.2f".format(it) } ?: "—")
      HudCell("前车加速", m.lead?.let { "%.2f".format(it.a) } ?: "—")
    }
  }
}

@Composable
private fun HudCell(label: String, value: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 10.sp)
    Text(value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
  }
}

@Composable
private fun StatusDot(ok: Boolean, text: String, amberWhenNotOk: Boolean = false) {
  val color = if (ok) Color(0xFF34D399) else if (amberWhenNotOk) Color(0xFFFBBF24) else Color(0xFFE24B4A)
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(
      modifier = Modifier
        .size(7.dp)
        .background(color, CircleShape)
    )
    Spacer(Modifier.width(6.dp))
    Text(text, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp)
  }
}

/**
 * 视频左侧的独立控制栏。放在 contain 布局留下的空白区，**不与视频内容重叠**。
 *
 * 三样东西：
 *   - 摄像头切换（广角 / 普通）：点击后经 :8085 通知 C3 侧 streamfwd 换源 + 换内参
 *   - 录制开关（圆形按钮）：复用 SuperVideoClient 的 H264 直存 MP4（零重编码、零画质损失）
 *   - 状态提示：切换结果 / 保存文件名
 */
@Composable
private fun CtrlRail(
  camMode: String,
  recording: Boolean,
  enabled: Boolean,
  note: String?,
  onCam: (String) -> Unit,
  onRec: () -> Unit
) {
  Column(
    modifier = Modifier
      .width(58.dp)
      .fillMaxHeight()
      .padding(end = 8.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    CamChip("广角", selected = camMode == "wide", enabled = enabled) { onCam("wide") }
    Spacer(Modifier.height(6.dp))
    CamChip("普通", selected = camMode != "wide", enabled = enabled) { onCam("narrow") }

    Spacer(Modifier.height(14.dp))

    // 录制按钮：录制中显示红色方块（停止），否则红色圆点（开始）
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(if (recording) Color(0xFF7F1D1D) else Color(0xFF1F2937))
        .border(1.dp, if (recording) Color(0xFFEF4444) else Color(0xFF374151), CircleShape)
        .clickable(enabled = enabled) { onRec() },
      contentAlignment = Alignment.Center
    ) {
      if (recording) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFEF4444)))
      } else {
        Box(
          Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(if (enabled) Color(0xFFEF4444) else Color(0xFF6B7280))
        )
      }
    }
    Spacer(Modifier.height(4.dp))
    Text(
      if (recording) "停止" else "录制",
      color = Color.White.copy(alpha = 0.75f),
      fontSize = 10.sp
    )

    Spacer(Modifier.height(12.dp))

    note?.let {
      Text(
        it,
        color = Color.White.copy(alpha = 0.55f),
        fontSize = 9.sp,
        lineHeight = 11.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
      )
    }

    Spacer(Modifier.weight(1f))
  }
}

@Composable
private fun CamChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(34.dp)
      .clip(RoundedCornerShape(8.dp))
      .background(if (selected) Color(0xFF1D4ED8) else Color(0xFF1F2937))
      .border(1.dp, if (selected) Color(0xFF60A5FA) else Color(0xFF374151), RoundedCornerShape(8.dp))
      .clickable(enabled = enabled) { onClick() },
    contentAlignment = Alignment.Center
  ) {
    Text(
      label,
      color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
      fontSize = 11.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
    )
  }
}

private const val VIDEO_PORT = 8083
private const val DATA_PORT = 8084
private const val STREAM_W = 1928
private const val STREAM_H = 1208
private const val LEAD_BUFF = 40f
private const val SPEED_BUFF = 10f
