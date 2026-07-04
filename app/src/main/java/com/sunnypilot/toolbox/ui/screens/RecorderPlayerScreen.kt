package com.sunnypilot.toolbox.ui.screens

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.RecorderRepository
import com.sunnypilot.toolbox.model.*
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun RecorderPlayerScreen(
    segmentId: String,
    sshManager: SshManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { RecorderRepository(context, sshManager) }

    var overlay by remember { mutableStateOf<RecorderOverlay?>(null) }
    var videoFile by remember { mutableStateOf<java.io.File?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadingMessage by remember { mutableStateOf("正在准备数据...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(segmentId) {
        loadingMessage = "正在读取叠加数据..."
        overlay = repository.ensureOverlay(segmentId).getOrElse {
            errorMessage = "读取 overlay 失败: ${it.message}"
            isLoading = false
            return@LaunchedEffect
        }
        loadingMessage = "正在下载视频..."
        videoFile = repository.ensureVideo(segmentId).getOrElse {
            errorMessage = "下载视频失败: ${it.message}"
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        PlayerTopBar(segmentId, onBack)

        Box(modifier = Modifier.weight(1f)) {
            if (isLoading || overlay == null || videoFile == null) {
                LoadingState(loadingMessage, errorMessage)
            } else {
                VideoPlayerWithOverlay(overlay!!, videoFile!!)
            }
        }
    }
}

@Composable
private fun PlayerTopBar(segmentId: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Slate900)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = segmentId,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun LoadingState(message: String, error: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null) {
                CircularProgressIndicator(color = Teal500)
                Spacer(modifier = Modifier.height(16.dp))
                Text(message, color = Slate600)
            } else {
                Icon(Icons.Default.Error, contentDescription = null, tint = Red500, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(error, color = Red500)
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayerWithOverlay(overlay: RecorderOverlay, videoFile: java.io.File) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(videoFile)))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> exoPlayer.play()
                Lifecycle.Event.ON_DESTROY -> exoPlayer.release()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    var currentPosition by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            isPlaying = exoPlayer.isPlaying
            delay(33)
        }
    }

    val frame = remember(overlay, currentPosition) {
        findNearestFrame(overlay, currentPosition)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        val calib = frame?.liveCalibration
        if (calib != null && overlay.cameraConfig != null) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val width = maxWidth.value
                val height = maxHeight.value
                val density = LocalContext.current.resources.displayMetrics.density
                val pxWidth = width * density
                val pxHeight = height * density

                OverlayCanvas(
                    frame = frame,
                    cameraConfig = overlay.cameraConfig,
                    rpyCalib = calib.rpyCalib,
                    widthPx = pxWidth,
                    heightPx = pxHeight,
                    pathHeightOffset = calib.height.firstOrNull() ?: 1.22f
                )
            }
        }

        frame?.let { HudOverlay(it, isPlaying, currentPosition) }
    }
}

@Composable
private fun OverlayCanvas(
    frame: RecorderFrame,
    cameraConfig: CameraConfigData,
    rpyCalib: List<Float>,
    widthPx: Float,
    heightPx: Float,
    pathHeightOffset: Float
) {
    val projection = remember(cameraConfig, rpyCalib, widthPx, heightPx) {
        RecorderProjection(
            cameraConfig = cameraConfig,
            rpyCalib = rpyCalib,
            contentWidth = widthPx,
            contentHeight = heightPx
        )
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val model = frame.modelV2 ?: return@Canvas

        // 车道线（白色，透明度按置信度）
        model.laneLines.forEachIndexed { idx, lane ->
            val prob = model.laneLineProbs.getOrNull(idx) ?: 0f
            drawPathLine(lane, projection, Color.White.copy(alpha = prob.coerceIn(0f, 0.7f)), 3f, 0f)
        }

        // 路边界（红色，按标准差调整透明度）
        model.roadEdges.forEachIndexed { idx, edge ->
            val std = model.roadEdgeStds.getOrNull(idx) ?: 1f
            val alpha = (1f - std).coerceIn(0f, 1f)
            drawPathLine(edge, projection, Color.Red.copy(alpha = alpha), 4f, 0f)
        }

        // 规划路径（绿色/青色）
        drawPathLine(
            model.position,
            projection,
            Color(0xFF00E676).copy(alpha = 0.8f),
            5f,
            pathHeightOffset
        )

        // 前车标记
        frame.radarState?.leadOne?.let { lead ->
            if (lead.status) {
                val pt = projection.project(lead.dRel, -lead.yRel, pathHeightOffset)
                pt?.let { (x, y) ->
                    drawCircle(
                        color = Color.Yellow.copy(alpha = 0.8f),
                        radius = 24f,
                        center = Offset(x, y)
                    )
                    drawCircle(
                        color = Color.Red.copy(alpha = 0.6f),
                        radius = 16f,
                        center = Offset(x, y)
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPathLine(
    xyz: XYZData,
    projection: RecorderProjection,
    color: Color,
    strokeWidth: Float,
    zOffset: Float
) {
    val pts = xyz.x.indices.mapNotNull { i ->
        projection.project(xyz.x[i], xyz.y[i], xyz.z[i] + zOffset)
    }
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(pts.first().first, pts.first().second)
        pts.drop(1).forEach { (x, y) -> lineTo(x, y) }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
}

@Composable
private fun HudOverlay(frame: RecorderFrame, isPlaying: Boolean, positionMs: Long) {
    val cs = frame.carState
    val ss = frame.selfdriveState
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            SpeedBox(cs?.vEgo ?: 0f)
            StatusBox(ss)
        }

        Spacer(modifier = Modifier.weight(1f))

        ss?.alertText1?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Color.Red,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
        ss?.alertText2?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Color.Red,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        if (!isPlaying) {
            Text(
                text = "暂停",
                color = Color.Yellow,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SpeedBox(vEgo: Float) {
    val kmh = (vEgo * 3.6f).toInt()
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = "$kmh", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(text = "km/h", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}

@Composable
private fun StatusBox(ss: SelfdriveStateData?) {
    val color = when {
        ss == null -> Slate500
        ss.enabled -> Green500
        else -> Slate500
    }
    val text = when {
        ss == null -> "未连接"
        ss.enabled -> "OP 启用"
        else -> "未启用"
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.8f), shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun findNearestFrame(overlay: RecorderOverlay, positionMs: Long): RecorderFrame? {
    if (overlay.frames.isEmpty()) return null
    val targetMono = overlay.startMonoTime + positionMs * 1_000_000L
    return overlay.frames.minByOrNull { kotlin.math.abs(it.logMonoTime - targetMono) }
}
