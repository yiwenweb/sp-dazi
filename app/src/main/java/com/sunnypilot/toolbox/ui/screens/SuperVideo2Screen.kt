package com.sunnypilot.toolbox.ui.screens

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.C3ScreenStreamDeployer
import com.sunnypilot.toolbox.data.repository.SuperVideo2Client
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 超级视频2 —— 独立于 Plan 1 的第二套投屏实现。
 *
 * ## 它是什么
 *
 * 复刻第三方 openpilot_toolbox v1.8.0 的 C3 投屏方案（**零 openpilot 依赖**）：
 * App 内置 payload（4 个 aarch64 ELF + 3 个脚本）→ SFTP 推到 C3 →
 * 执行 install.sh → 拉起三级管道 → 视频 TCP:5000 / 触控 TCP:27184。
 *
 * ## 与「视频预览」（Plan 1）的区别
 *
 * | | 视频预览（Plan 1） | 超级视频2（本页） |
 * |---|---|---|
 * | C3 依赖 | 需我们的 `supervideo.cc` | **零 openpilot 依赖** |
 * | 视频端口 | :8082 | **:5000** |
 * | 触控 | HTTP :8081/input | **TCP :27184 二进制协议** |
 * | 分辨率 | 1280×640 | **1024×512** |
 * | 帧率 | 30fps | 20fps |
 * | 部署 | 需同步 sunnypilot 仓库 | **App 自己推 payload** |
 *
 * ## 使用流程（一键）
 *
 * 点「一键部署并连接」→ 自动走完 6 个阶段 → 自动拉流 → 触摸回控。
 */
@Composable
fun SuperVideo2Screen(
  sshManager: SshManager,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val mainHandler = remember { Handler(Looper.getMainLooper()) }
  val host = remember { sshManager.connectedHost }

  // ── 部署状态 ──
  var deploying by remember { mutableStateOf(false) }
  var deployStage by remember { mutableStateOf("") }
  var deployLog by remember { mutableStateOf<List<String>>(emptyList()) }
  var deployFailed by remember { mutableStateOf(false) }
  var remoteVersion by remember { mutableStateOf("") }

  // ── 拉流状态 ──
  var streaming by remember { mutableStateOf(false) }
  var sConnected by remember { mutableStateOf(false) }
  var fps by remember { mutableIntStateOf(0) }
  var streamW by remember { mutableIntStateOf(SuperVideo2Client.STREAM_WIDTH) }
  var streamH by remember { mutableIntStateOf(SuperVideo2Client.STREAM_HEIGHT) }
  var diag by remember { mutableStateOf<List<String>>(emptyList()) }
  var touchOk by remember { mutableStateOf(false) }

  val clientRef = remember { mutableStateOf<SuperVideo2Client?>(null) }
  val textureViewRef = remember { mutableStateOf<TextureView?>(null) }
  val deployerRef = remember { mutableStateOf<C3ScreenStreamDeployer?>(null) }

  // 视图尺寸（触摸坐标映射用）
  var viewW by remember { mutableIntStateOf(0) }
  var viewH by remember { mutableIntStateOf(0) }
  var surfaceGen by remember { mutableIntStateOf(0) }

  // ── 日志面板是否展开 ──
  var showLog by remember { mutableStateOf(false) }

  // ═══════════════════ 一键部署 + 拉流 ═══════════════════

  // 注意：Kotlin 不允许在局部作用域内**前向引用**局部函数，
  // 因此 startStream / stopAll 必须声明在 startEverything 之前。
  fun startStream() {
    val h = host ?: return
    if (clientRef.value != null) return
    val c = SuperVideo2Client(
      host = h,
      videoPort = C3ScreenStreamDeployer.VIDEO_PORT,
      touchPort = C3ScreenStreamDeployer.TOUCH_PORT,
      onFps = { fps = it },
      onStatus = { sConnected = it },
      onResolution = { w, hh -> streamW = w; streamH = hh },
      onDiag = { diag = it },
      // 端口拒绝连接时，直接把 C3 的抓屏/编码日志拉到面板上，
      // 这样不用 SSH 就能看到 `rotator frame error` 之类的真实原因。
      onRemoteLog = { reason ->
        val base = C3ScreenStreamDeployer.BASE_DIR
        val cmd = "echo \"-- 抓屏 --\"; tail -6 $base/capture.log 2>/dev/null || true; " +
          "echo \"-- 编码 --\"; tail -4 $base/encoder.log 2>/dev/null || true; " +
          "echo \"-- 进程 --\"; pgrep -af 'sde_rotator_stream|v4l_h264_encoder|swscale_xrgb' " +
          "2>/dev/null || echo '(上游进程均未运行)'"
        sshManager.executeCommand(cmd).getOrElse { "拉取远端日志失败：${it.message}" }
          .lines().filter { it.isNotBlank() }.take(16)
      }
    )
    c.setFallbackSurfaceProvider {
      val tv = textureViewRef.value
      if (tv != null && tv.isAvailable && tv.surfaceTexture != null) Surface(tv.surfaceTexture)
      else null
    }
    clientRef.value = c
    c.start()
    streaming = true
  }

  fun stopAll() {
    clientRef.value?.stop()
    clientRef.value = null
    streaming = false
    sConnected = false
    fps = 0
    diag = emptyList()
  }

  fun startEverything() {
    if (host.isNullOrBlank()) {
      Toast.makeText(context, "请先在连接中心建立与 C3 的 SSH 连接", Toast.LENGTH_LONG).show()
      return
    }
    scope.launch {
      deploying = true
      deployFailed = false
      deployLog = emptyList()
      deployStage = "准备中…"

      val deployer = deployerRef.value ?: C3ScreenStreamDeployer(sshManager, context).also {
        deployerRef.value = it
      }

      val result = withContext(Dispatchers.IO) {
        deployer.deploy(
          onStage = { stage, desc ->
            mainHandler.post { deployStage = "${stage.label}：$desc" }
          },
          onLog = { line ->
            mainHandler.post { deployLog = deployLog + line }
          }
        )
      }

      deploying = false
      when (result) {
        is C3ScreenStreamDeployer.Result2.Ok -> {
          deployStage = "部署完成，正在拉流…"
          remoteVersion = withContext(Dispatchers.IO) { deployer.remoteVersion() }
          startStream()
        }
        is C3ScreenStreamDeployer.Result2.Fail -> {
          deployFailed = true
          deployStage = "失败于「${result.stage.label}」：${result.message}"
          showLog = true
          Log.w("SuperVideo2UI", "deploy failed at ${result.stage}: ${result.message}")
        }
      }
    }
  }

  // 离开页面时停掉拉流（推流进程留在 C3，下次进来重连即可）
  DisposableEffect(Unit) {
    onDispose { stopAll() }
  }

  // 触摸连通状态轮询（每 2 秒刷新一次显示）
  LaunchedEffect(streaming) {
    while (streaming) {
      touchOk = clientRef.value?.touchConnected == true
      kotlinx.coroutines.delay(2000)
    }
  }

  // Surface 双向收敛（Compose 中 AndroidView.factory 与 DisposableEffect 顺序不定）
  LaunchedEffect(streaming, surfaceGen, clientRef.value) {
    val c = clientRef.value ?: return@LaunchedEffect
    val tv = textureViewRef.value ?: return@LaunchedEffect
    if (streaming && tv.isAvailable && tv.surfaceTexture != null) {
      c.setSurface(Surface(tv.surfaceTexture))
    }
  }

  // ═══════════════════ UI ═══════════════════

  Column(
    modifier = modifier.fillMaxSize().background(Color(0xFF0B0F14))
  ) {
    // ── 顶部工具条 ──
    Surface(color = Color(0xFF111827), tonalElevation = 0.dp) {
      Row(
        modifier = Modifier.fillMaxWidth().height(54.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        val busy = deploying
        Button(
          onClick = {
            if (streaming) {
              stopAll()
            } else {
              startEverything()
            }
          },
          enabled = !busy,
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = if (streaming) Red600 else Teal500,
            contentColor = Color.White
          )
        ) {
          if (busy) {
            CircularProgressIndicator(
              color = Color.White,
              strokeWidth = 2.dp,
              modifier = Modifier.size(16.dp)
            )
          } else {
            Icon(
              imageVector = if (streaming) Icons.Default.Stop else Icons.Default.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
          }
          Spacer(Modifier.width(6.dp))
          Text(
            when {
              busy -> "部署中…"
              streaming -> "停止"
              else -> "一键部署并连接"
            },
            fontSize = 13.sp
          )
        }

        Divider(
          modifier = Modifier.height(24.dp).width(1.dp),
          color = Color.White.copy(alpha = 0.12f)
        )

        InfoChip2(Icons.Default.Videocam, "帧率", if (sConnected) "$fps FPS" else "—")
        InfoChip2(Icons.Default.HighQuality, "分辨率", "$streamW × $streamH")
        InfoChip2(
          Icons.Default.TouchApp, "触摸",
          if (touchOk) "已连通" else if (streaming) "连接中" else "—"
        )

        Spacer(Modifier.weight(1f))

        // 日志开关
        IconButton(onClick = { showLog = !showLog }) {
          Icon(
            if (showLog) Icons.Default.BugReport else Icons.Default.Article,
            contentDescription = "日志",
            tint = if (showLog) Teal500 else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
          )
        }

        // 连接状态灯
        val effConnected = sConnected
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(8.dp).background(if (effConnected) Green500 else Amber500, CircleShape))
          Spacer(Modifier.width(6.dp))
          Text(
            when {
              host.isNullOrBlank() -> "未连接 C3"
              effConnected -> "已连接"
              streaming -> "拉流中…"
              else -> "待启动"
            },
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 12.sp
          )
        }
      }
    }

    // ── 视频区 ──
    Box(
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .background(Color.Black)
        .onSizeChanged { viewW = it.width; viewH = it.height }
        .pointerInput(host, streamW, streamH, viewW, viewH, streaming) {
          if (!streaming || host.isNullOrBlank()) return@pointerInput
          // 触摸映射：容器像素 → contain 内容区 → 归一化到 0..w-1 / 0..h-1
          //
          // 注意：这里送的是**归一化坐标**，不是 C3 的 1080x2160 原始坐标。
          // 第三方 touch_proxy.py 自己会做：
          //   raw_x = 1080 - scale(y, height - 1, 1080)
          //   raw_y =        scale(x, width  - 1, 2160)
          // 我们只需保证 x∈[0,w-1]、y∈[0,h-1]。第三方 App 侧就是这么做的
          // （cb/g4.java j3() 把坐标 clamp 到 (0, i11-1) 后放进 v5(action, x, y, w, h)）。
          fun map(px: Float, py: Float): Pair<Int, Int> {
            if (viewW <= 0 || viewH <= 0) return 0 to 0
            val sc = min(viewW.toFloat() / streamW, viewH.toFloat() / streamH)
            val dw = streamW * sc
            val dh = streamH * sc
            val dx = (viewW - dw) / 2f
            val dy = (viewH - dh) / 2f
            val fx = ((px - dx) / dw).coerceIn(0f, 1f)
            val fy = ((py - dy) / dh).coerceIn(0f, 1f)
            return (fx * (streamW - 1)).roundToInt().coerceIn(0, streamW - 1) to
              (fy * (streamH - 1)).roundToInt().coerceIn(0, streamH - 1)
          }
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val d = map(down.position.x, down.position.y)
            clientRef.value?.sendTouch(
              SuperVideo2Client.ACTION_DOWN, d.first, d.second, streamW, streamH
            )
            var last = d
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Main)
              val change = event.changes.firstOrNull() ?: break
              if (change.changedToUpIgnoreConsumed()) {
                clientRef.value?.sendTouch(
                  SuperVideo2Client.ACTION_UP, 0, 0, streamW, streamH
                )
                break
              }
              val p = map(change.position.x, change.position.y)
              if (abs(p.first - last.first) + abs(p.second - last.second) >= 2) {
                clientRef.value?.sendTouch(
                  SuperVideo2Client.ACTION_MOVE, p.first, p.second, streamW, streamH
                )
                last = p
              }
            }
          }
        }
    ) {
      if (streaming) {
        // ===== TextureView + MediaCodec Surface 渲染 =====
        // 关键：SurfaceTexture 默认缓冲尺寸 0x0，必须显式 setDefaultBufferSize，
        // 否则 MediaCodec 渲染到 0x0 → 全黑（Plan 1 的踩坑，这里同样适用）。
        AndroidView(
          factory = { ctx ->
            TextureView(ctx).apply {
              surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                  st: android.graphics.SurfaceTexture, w: Int, h: Int
                ) {
                  st.setDefaultBufferSize(streamW, streamH)
                  clientRef.value?.setSurface(Surface(st))
                  surfaceGen++
                }

                override fun onSurfaceTextureSizeChanged(
                  st: android.graphics.SurfaceTexture, w: Int, h: Int
                ) {
                  st.setDefaultBufferSize(streamW, streamH)
                  clientRef.value?.setSurface(Surface(st))
                  surfaceGen++
                }

                override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                  clientRef.value?.setSurface(null)
                  return true
                }

                override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
              }
              textureViewRef.value = this
            }
          },
          modifier = Modifier.fillMaxSize()
        )

        if (!sConnected) {
          Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            CircularProgressIndicator(color = Teal500, strokeWidth = 2.dp, modifier = Modifier.size(34.dp))
            Spacer(Modifier.height(10.dp))
            Text(
              "正在连接 C3 投屏流（:$C3_SCREEN_VIDEO_PORT）…",
              color = Color.White.copy(alpha = 0.8f),
              fontSize = 13.sp
            )
          }
        }
      } else {
        // ===== 未启动：引导卡片 =====
        Column(
          modifier = Modifier.align(Alignment.Center).padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(
            Icons.Default.CastConnected,
            contentDescription = null,
            tint = Teal500,
            modifier = Modifier.size(52.dp)
          )
          Spacer(Modifier.height(14.dp))
          Text(
            "超级视频2",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(Modifier.height(6.dp))
          Text(
            "零 openpilot 依赖的独立投屏方案",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp
          )
          Spacer(Modifier.height(18.dp))

          if (deploying || deployStage.isNotEmpty()) {
            Surface(
              color = Color(0xFF1A2332),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth(0.9f)
            ) {
              Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  if (deploying) {
                    CircularProgressIndicator(
                      color = Teal500, strokeWidth = 2.dp,
                      modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                  } else {
                    Icon(
                      if (deployFailed) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                      contentDescription = null,
                      tint = if (deployFailed) Red600 else Green500,
                      modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                  }
                  Text(
                    deployStage,
                    color = if (deployFailed) Color(0xFFFF8A80) else Color(0xFF7FE7C4),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                  )
                }
                if (remoteVersion.isNotBlank()) {
                  Spacer(Modifier.height(4.dp))
                  Text(
                    "payload 版本：$remoteVersion",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                  )
                }
              }
            }
          } else {
            Text(
              "点上方「一键部署并连接」自动完成：\n上传 payload → 安装 → 启动推流 → 拉流",
              color = Color.White.copy(alpha = 0.6f),
              fontSize = 12.sp,
              lineHeight = 18.sp
            )
          }
        }
      }

      // ── 诊断/日志面板（左下角浮层）──
      if (showLog && deployLog.isNotEmpty() && !streaming) {
        Surface(
          color = Color(0xE6000000),
          shape = RoundedCornerShape(6.dp),
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(6.dp)
            .fillMaxWidth(0.94f)
            .heightIn(max = 200.dp)
        ) {
          Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
            Text(
              "部署日志 (${deployLog.size})",
              color = Color(0xFF7FE7C4),
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            deployLog.forEach { line ->
              Text(
                line,
                color = when {
                  line.startsWith("✓") -> Color(0xFF7FE7C4)
                  line.startsWith("✗") -> Color(0xFFFF8A80)
                  line.startsWith("⚠") -> Color(0xFFFFD180)
                  else -> Color.White.copy(alpha = 0.65f)
                },
                fontSize = 9.sp,
                lineHeight = 12.sp,
                fontFamily = FontFamily.Monospace
              )
            }
          }
        }
      }

      // ── 解码诊断（左下角浮层，拉流中显示）──
      if (streaming && diag.isNotEmpty()) {
        Column(
          modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(6.dp)
            .background(Color(0xCC000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
          for (line in diag) {
            Text(
              line,
              color = Color(0xFF7FE7C4),
              fontSize = 9.sp,
              lineHeight = 11.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1
            )
          }
        }
      }
    }
  }
}

@Composable
private fun InfoChip2(
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

/** 页面内展示用（避免直接依赖 Deployer 常量造成循环引用的可读性问题）。 */
private const val C3_SCREEN_VIDEO_PORT = 5000
