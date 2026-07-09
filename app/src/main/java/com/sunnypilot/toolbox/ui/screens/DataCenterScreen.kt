@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.sunnypilot.toolbox.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.DriveStatsRepository
import com.sunnypilot.toolbox.data.sync.SyncStateHolder
import com.sunnypilot.toolbox.data.sync.SyncStatus
import com.sunnypilot.toolbox.model.AggregatedStats
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataCenterScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DriveStatsRepository(context, sshManager) }

    var stats by remember { mutableStateOf<AggregatedStats?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var autoRefresh by remember { mutableStateOf(true) }
    var showDateDialog by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(DriveStatsRepository.dateRange(30).first) }
    var endDate by remember { mutableStateOf(DriveStatsRepository.dateRange(30).second) }

    // 数据概况弹窗（扳手第一步—看数据，确定后再启动获取）
    var showSyncOverview by remember { mutableStateOf(false) }

    // 观察 SyncStateHolder 的后台同步状态（进程级，离开页面也不会停）
    val syncStatus by SyncStateHolder.status.collectAsState()
    val syncStageText by SyncStateHolder.stageText.collectAsState()
    val syncError by SyncStateHolder.errorMessage.collectAsState()
    val syncedCount by SyncStateHolder.syncedCount.collectAsState()

    fun loadStats() {
        scope.launch {
            val data = repository.aggregate(startDate, endDate)
            stats = data
        }
    }

    // 同步完成后自动刷新显示
    LaunchedEffect(syncedCount) {
        if (syncedCount != null) {
            delay(600)
            loadStats()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                repository.importFromJson(context, it).fold(
                    onSuccess = { count ->
                        Toast.makeText(context, "导入 $count 条记录", Toast.LENGTH_SHORT).show()
                        loadStats()
                    },
                    onFailure = { e ->
                        Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    fun exportStats() {
        scope.launch {
            val uri = repository.exportToJson(context, startDate, endDate)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "导出驾驶数据"))
        }
    }

    LaunchedEffect(Unit) {
        val all = repository.getAll()
        if (all.isEmpty() && sshManager.isConnected()) {
            SyncStateHolder.start(context, sshManager)
        }
        loadStats()
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 左列：总报
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SummaryPanel(
                stats = stats,
                startDate = startDate,
                autoRefresh = autoRefresh,
                onAutoRefreshChange = { autoRefresh = it },
                onRefresh = { loadStats() },
                onSync = { showSyncOverview = true },
                isLoading = isLoading || SyncStateHolder.isRunning
            )
        }

        // 右列：ADS 里程 + 驾驶效率
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AdsMileagePanel(
                stats = stats,
                onExport = { exportStats() },
                onImport = { importLauncher.launch("application/json") }
            )
            EfficiencyPanel(stats = stats)
        }
    }

    if (showDateDialog) {
        DateRangeDialog(
            initialStart = startDate,
            initialEnd = endDate,
            onDismiss = { showDateDialog = false },
            onConfirm = { s, e ->
                startDate = s
                endDate = e
                showDateDialog = false
                loadStats()
            }
        )
    }

    // ── 数据概况弹窗（扳手的第一步） ──
    if (showSyncOverview) {
        val earliest = SyncStateHolder.getEarliestStoredDate(context)
        val latest = SyncStateHolder.getLatestStoredDate(context)
        val lastSyncAt = SyncStateHolder.lastSyncAt.collectAsState().value
        AlertDialog(
            onDismissRequest = { showSyncOverview = false },
            title = { Text("数据概况", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("当前本地已有数据：", fontWeight = FontWeight.SemiBold, color = Slate700)
                    if (earliest != null && latest != null) {
                        Text("范围：$earliest ～ $latest", fontSize = 14.sp, color = Slate600)
                    } else {
                        Text("（暂无本地数据）", fontSize = 14.sp, color = Slate400)
                    }
                    lastSyncAt?.let {
                        Text("上次同步：$it", fontSize = 13.sp, color = Slate500)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "点击下方「获取更新数据」将从 C3 拉取最新行车记录并替换本地数据。\n" +
                                "同步在后台运行，可自由切换页面。",
                        fontSize = 13.sp,
                        color = Slate500,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSyncOverview = false
                    SyncStateHolder.start(context, sshManager)
                }) { Text("获取更新数据", color = Teal500) }
            },
            dismissButton = {
                TextButton(onClick = { showSyncOverview = false }) { Text("取消", color = Slate500) }
            }
        )
    }

    // ── 后台同步进度弹窗 ──
    if (syncStatus != SyncStatus.IDLE) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("正在获取数据", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = Teal500,
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(syncStageText, fontSize = 15.sp, color = Slate700)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("后台运行中，可切到其他页面", fontSize = 12.sp, color = Slate400)
                }
            },
            confirmButton = {}
        )
    }

    // ── 同步错误弹窗 ──
    syncError?.let { err ->
        AlertDialog(
            onDismissRequest = { SyncStateHolder.dismissError() },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Red500, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("同步失败", color = Red500, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text("错误详情：", fontWeight = FontWeight.SemiBold, color = Slate700)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Red100,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            err,
                            fontSize = 13.sp,
                            color = Red500,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("常见原因：", fontSize = 12.sp, color = Slate500)
                    Text("  • C3 未连接或 SSH 断开", fontSize = 12.sp, color = Slate500)
                    Text("  • C3 上的脚本 calc_drive_stats.py 缺失", fontSize = 12.sp, color = Slate500)
                    Text("  • C3 realdata 中没有行车数据", fontSize = 12.sp, color = Slate500)
                }
            },
            confirmButton = {
                TextButton(onClick = { SyncStateHolder.dismissError() }) { Text("知道了", color = Teal500) }
            }
        )
    }
}

@Composable
private fun SummaryPanel(
    stats: AggregatedStats?,
    startDate: String,
    autoRefresh: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    isLoading: Boolean
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("总报", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate900)
                    Text(
                        "统计起点 $startDate 22:05",
                        fontSize = 12.sp,
                        color = Slate500
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("实时更新", fontSize = 13.sp, color = Slate600)
                    Spacer(modifier = Modifier.width(6.dp))
                    Switch(
                        checked = autoRefresh,
                        onCheckedChange = onAutoRefreshChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Teal500,
                            checkedTrackColor = Teal100
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Teal500)
                    }
                    IconButton(onClick = onSync, enabled = !isLoading) {
                        Icon(Icons.Default.Build, contentDescription = "修复", tint = Slate600)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val s = stats ?: AggregatedStats(
                startDate = startDate,
                totalDistanceKm = 0f,
                assistedDistanceKm = 0f,
                manualDistanceKm = 0f,
                assistedPercent = 0,
                manualPercent = 0,
                durationMinutes = 0,
                assistedDurationMinutes = 0,
                durationRatioPercent = 0,
                safetyScore = 0,
                takeovers = 0,
                collisionWarning = 0,
                tailgating = 0,
                leadCarStationary = 0,
                leadCarEmergencyBrake = 0,
                leadCarSlow = 0,
                startReminder = 0,
                laneChangeAssist = 0
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CircularStat("占总里程", s.assistedPercent, "${s.assistedDistanceKm} km", "驾驶辅助里程", Teal500)
                CircularStat("人工占比", s.manualPercent, "${s.manualDistanceKm} km", "人工驾驶里程", Blue500)
                CircularStat("占行驶时长", s.durationRatioPercent, formatDuration(s.assistedDurationMinutes), "智驾辅助时长", Amber500)
                CircularStat("安全评分", s.safetyScore, "${s.safetyScore} 分", "安全评分", Color(0xFFF59E0B))
            }

            Spacer(modifier = Modifier.height(16.dp))

            val items = listOf(
                Triple("接管次数", s.takeovers.toString(), Red500),
                Triple("碰撞预警", s.collisionWarning.toString(), Red500),
                Triple("跟车过近", s.tailgating.toString(), Amber500),
                Triple("前车静止", s.leadCarStationary.toString(), Blue500),
                Triple("前车急刹", s.leadCarEmergencyBrake.toString(), Red500),
                Triple("前车龟速", s.leadCarSlow.toString(), Blue500),
                Triple("起步提醒", s.startReminder.toString(), Teal500),
                Triple("变道辅助", s.laneChangeAssist.toString(), Blue500)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items.forEach { (title, value, color) ->
                    AlertCard(title, value, color)
                }
            }
        }
    }
}

@Composable
private fun AdsMileagePanel(
    stats: AggregatedStats?,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("ADS 里程", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate900)
                    Text("辅助驾驶与人工驾驶里程分布", fontSize = 12.sp, color = Slate500)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onExport) {
                        Icon(Icons.Default.Upload, contentDescription = "导出", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导出", fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = onImport) {
                        Icon(Icons.Default.Download, contentDescription = "导入", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("导入", fontSize = 13.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val s2 = stats
            if (s2 != null && s2.totalDistanceKm > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                        CanvasRing(
                            assisted = s2.assistedDistanceKm,
                            manual = s2.manualDistanceKm,
                            total = s2.totalDistanceKm
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${s2.totalDistanceKm}", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Slate900)
                            Text("总里程(公里)", fontSize = 11.sp, color = Slate500)
                            Text("智驾 ${s2.assistedPercent}%\n车主 ${s2.manualPercent}%", fontSize = 10.sp, color = Slate500)
                        }
                    }
                    Spacer(modifier = Modifier.width(24.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LegendItem(Teal500, "智驾辅助里程", "${s2.assistedDistanceKm} 公里")
                        LegendItem(Blue500, "驾驶员里程", "${s2.manualDistanceKm} 公里")
                        LegendItem(Slate600, "总里程", "${s2.totalDistanceKm} 公里")
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    Text("暂无里程数据，点击刷新或同步", color = Slate500)
                }
            }
        }
    }
}

@Composable
private fun EfficiencyPanel(stats: AggregatedStats?) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("驾驶效率", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate900)
            Text("里程、时长和速度的核心效率指标", fontSize = 12.sp, color = Slate500)

            Spacer(modifier = Modifier.height(16.dp))

            val s3 = stats
            fun fmt1(v: Float?) = String.format("%.1f", v ?: 0f)
            val efficiencyItems = listOf(
                Triple("单次最长", "${fmt1(s3?.longestSingleDistanceKm)} km", Amber500),
                Triple("持续最长", formatDuration(s3?.continuousOpMinutes ?: 0), Teal500),
                Triple("千公里接管", "${s3?.takeoversPerKkm?.let { String.format("%.2f", it) } ?: "0.00"}", Red500),
                Triple("平均速度", "${fmt1(s3?.avgSpeedKmh)} km/h", Blue500),
                Triple("最高速度", "${fmt1(s3?.maxSpeedKmh)} km/h", Blue500),
                Triple("总时长", formatDuration(s3?.durationMinutes ?: 0), Amber500)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                efficiencyItems.forEach { (title, value, color) ->
                    MetricCard(title, value, color)
                }
            }
        }
    }
}


@Composable
private fun CircularStat(label: String, percent: Int, value: String, subLabel: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = percent / 100f,
                modifier = Modifier.fillMaxSize(),
                color = color,
                trackColor = color.copy(alpha = 0.15f),
                strokeWidth = 6.dp,
                strokeCap = StrokeCap.Round
            )
            Text("$percent%", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Slate900)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Slate900)
        Text(subLabel, fontSize = 11.sp, color = Slate500)
    }
}

@Composable
private fun AlertCard(title: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.08f),
        modifier = Modifier.width(100.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate900)
            Text(title, fontSize = 11.sp, color = Slate600)
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.08f),
        modifier = Modifier.width(120.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Slate900)
            Text(title, fontSize = 11.sp, color = Slate600)
        }
    }
}

@Composable
private fun CanvasRing(assisted: Float, manual: Float, total: Float) {
    val assistedSweep = if (total > 0) (assisted / total) * 360f else 0f
    val manualSweep = if (total > 0) (manual / total) * 360f else 0f
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 18.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = Teal500,
            startAngle = -90f,
            sweepAngle = assistedSweep,
            useCenter = false,
            style = stroke
        )
        drawArc(
            color = Blue500,
            startAngle = -90f + assistedSweep,
            sweepAngle = manualSweep,
            useCenter = false,
            style = stroke
        )
    }
}

@Composable
private fun LegendItem(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(label, fontSize = 12.sp, color = Slate600)
            Text(value, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Slate900)
        }
    }
}

@Composable
private fun DateRangeDialog(
    initialStart: String,
    initialEnd: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var start by remember { mutableStateOf(initialStart) }
    var end by remember { mutableStateOf(initialEnd) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择日期范围") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("开始日期") })
                OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("结束日期") })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(start, end) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun formatDuration(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return "${h}h ${m}m"
}


