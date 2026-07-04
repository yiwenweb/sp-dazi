package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.RecorderRepository
import com.sunnypilot.toolbox.data.repository.SegmentSummary
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun RecorderScreen(
    sshManager: SshManager,
    onPlay: (segmentId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { RecorderRepository(context, sshManager) }

    var segments by remember { mutableStateOf<List<SegmentSummary>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedSegment by remember { mutableStateOf<String?>(null) }

    fun loadSegments() {
        scope.launch {
            isLoading = true
            repository.listSegments().fold(
                onSuccess = { segments = it.reversed() },
                onFailure = { e ->
                    Toast.makeText(context, "读取 segment 失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadSegments()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "行车记录仪回放",
                style = MaterialTheme.typography.headlineMedium,
                color = Slate900,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { loadSegments() }, enabled = !isLoading) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新",
                    tint = Teal500
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "从 C3 同步 qcamera.ts 与 overlay.json 到本机后播放。首次使用请先对 segment 做预处理。",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading && segments.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal500)
            }
        } else if (segments.isEmpty()) {
            EmptyRecorderState()
        } else {
            segments.forEach { seg ->
                SegmentCard(
                    segment = seg,
                    selected = selectedSegment == seg.segmentId,
                    onClick = { selectedSegment = seg.segmentId },
                    onPreprocess = {
                        scope.launch {
                            isLoading = true
                            repository.preprocessSegment(seg.segmentId).fold(
                                onSuccess = {
                                    Toast.makeText(context, "预处理完成", Toast.LENGTH_SHORT).show()
                                    loadSegments()
                                },
                                onFailure = { e ->
                                    Toast.makeText(context, "预处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                            isLoading = false
                        }
                    },
                    onPlay = { onPlay(seg.segmentId) },
                    onDeleteCache = {
                        scope.launch {
                            repository.deleteLocalCache(seg.segmentId).fold(
                                onSuccess = {
                                    Toast.makeText(context, "已删除本地缓存", Toast.LENGTH_SHORT).show()
                                    loadSegments()
                                },
                                onFailure = { e ->
                                    Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SegmentCard(
    segment: SegmentSummary,
    selected: Boolean,
    onClick: () -> Unit,
    onPreprocess: () -> Unit,
    onPlay: () -> Unit,
    onDeleteCache: () -> Unit
) {
    val ready = segment.hasOverlay && segment.hasVideo
    val hasLocalCache = segment.cachedOverlay || segment.cachedVideo
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Teal50 else Slate50),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.VideoFile,
                    contentDescription = null,
                    tint = if (ready) Teal500 else Slate400,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = segment.segmentId,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row {
                        StatusChip("视频", segment.hasVideo)
                        Spacer(modifier = Modifier.width(4.dp))
                        StatusChip("叠加数据", segment.hasOverlay)
                        if (hasLocalCache) {
                            Spacer(modifier = Modifier.width(4.dp))
                            StatusChip("本地缓存", true)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!segment.hasOverlay) {
                    OutlinedButton(onClick = onPreprocess) {
                        Text("预处理")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (hasLocalCache) {
                    OutlinedButton(
                        onClick = onDeleteCache,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Slate600)
                    ) {
                        Text("删除缓存")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Button(
                    onClick = onPlay,
                    enabled = ready,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("播放")
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean) {
    val bg = if (ok) Teal100 else Slate200
    val fg = if (ok) Teal700 else Slate500
    Text(
        text = "$label ${if (ok) "✓" else "×"}",
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun EmptyRecorderState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp)
    ) {
        Icon(
            imageVector = Icons.Default.VideoLibrary,
            contentDescription = null,
            tint = Slate400,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text("暂无 segment", color = Slate600, style = MaterialTheme.typography.bodyLarge)
    }
}
