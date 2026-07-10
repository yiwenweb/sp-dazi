package com.sunnypilot.toolbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.DeviceStatus
import com.sunnypilot.toolbox.model.ServiceStatus
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DeviceManagerScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    var status by remember { mutableStateOf(DeviceStatus()) }
    var healthScore by remember { mutableStateOf(100) }
    var scoreLabel by remember { mutableStateOf("设备体态良好") }
    var isChecking by remember { mutableStateOf(false) }
    var showCleanupConfirm by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var cleanupResult by remember { mutableStateOf("") }
    var isCleaning by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf("") }
    var selectedSuggestion by remember { mutableStateOf<Suggestion?>(null) }
    var showSuggestionDialog by remember { mutableStateOf(false) }
    var showServiceDetailDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val suggestions = remember(status, healthScore) { generateSuggestions(status, healthScore) }

    fun performCheck() {
        scope.launch {
            isChecking = true
            refreshStatus(sshManager) {
                status = it
                val (score, label) = calculateHealthScore(it)
                healthScore = score
                scoreLabel = label
            }
            isChecking = false
        }
    }

    LaunchedEffect(sshManager.isConnected()) {
        if (sshManager.isConnected()) {
            performCheck()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 标题栏
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "设备管家",
                style = MaterialTheme.typography.headlineLarge,
                color = Slate900
            )
            Text(
                text = "管理 openpilot 设备运行状态，进行体检、清理与释放",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 左侧：健康分 + 指标 + 服务状态
            Column(
                modifier = Modifier.weight(1.5f),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 健康分卡片
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Panel)
                        .padding(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    HealthScoreCircle(score = healthScore)

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = scoreLabel,
                                style = MaterialTheme.typography.headlineMedium,
                                color = Slate900,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                tint = Green500,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionChip(
                                text = "立即体检",
                                icon = Icons.Default.FactCheck,
                                color = Blue500,
                                bgColor = Blue100,
                                onClick = { performCheck() }
                            )
                            ActionChip(
                                text = "刷新状态",
                                icon = Icons.Default.Refresh,
                                color = Teal500,
                                bgColor = Teal50,
                                onClick = { performCheck() }
                            )
                            ActionChip(
                                text = "导出日志",
                                icon = Icons.Default.FileDownload,
                                color = Slate600,
                                bgColor = Slate200,
                                onClick = {
                                    scope.launch {
                                        exportLogs(sshManager) { exportResult = it }
                                        showExportDialog = true
                                    }
                                }
                            )
                        }
                    }
                }

                // 核心指标
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MetricCard(
                        label = "当前温度",
                        value = "${status.cpuTemp}°C",
                        icon = Icons.Default.Thermostat,
                        iconColor = if (status.cpuTemp > 75f) Red500 else Amber500,
                        bgColor = if (status.cpuTemp > 75f) Red100 else Amber100,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "内存占用",
                        value = "${status.memoryUsage}%",
                        icon = Icons.Default.Memory,
                        iconColor = Blue500,
                        bgColor = Blue100,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "内置存储",
                        value = status.storageFree,
                        icon = Icons.Default.Storage,
                        iconColor = Teal500,
                        bgColor = Teal50,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "外接固态",
                        value = status.storageFreeSsd,
                        icon = Icons.Default.SdStorage,
                        iconColor = Blue500,
                        bgColor = Blue100,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 服务状态
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusCard(
                        label = "openpilot 服务",
                        value = if (status.openpilotService) "正常" else "异常",
                        isGood = status.openpilotService,
                        icon = if (status.openpilotService) Icons.Default.CheckCircle else Icons.Default.Error,
                        subtitle = if (status.abnormalServices.isNotEmpty())
                            "${status.abnormalServices.size}项异常" else null,
                        onClick = { showServiceDetailDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        label = "Panda 通信",
                        value = if (status.pandaComm) "正常" else "异常",
                        isGood = status.pandaComm,
                        icon = if (status.pandaComm) Icons.Default.CheckCircle else Icons.Default.Error,
                        subtitle = if (status.pandaComm) "已连接" else "未检测到",
                        onClick = { showServiceDetailDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 功能卡片
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FeatureCard(
                        title = "设备体检",
                        description = "全面检测温度、内存、存储、服务状态及 Panda 通信等关键指标。",
                        icon = Icons.Default.FactCheck,
                        iconColor = Blue500,
                        iconBg = Blue100,
                        modifier = Modifier.weight(1f),
                        onClick = { performCheck() }
                    )
                    FeatureCard(
                        title = "垃圾清理与内存释放",
                        description = "深度清理系统无用日志与缓存，同时一键优化并释放车机系统缓存，提高运行效率。",
                        icon = Icons.Default.AutoFixHigh,
                        iconColor = Green500,
                        iconBg = Green100,
                        badge = "可优化",
                        modifier = Modifier.weight(1f),
                        onClick = { showCleanupConfirm = true }
                    )
                }
            }

            // 右侧：风险与建议
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Panel)
                    .padding(24.dp)
            ) {
                Text(
                    text = "风险与建议",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Slate900,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    suggestions.forEach { suggestion ->
                        SuggestionItem(
                            suggestion = suggestion,
                            onClick = {
                                selectedSuggestion = suggestion
                                showSuggestionDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (isChecking || isCleaning) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Teal500)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    if (isCleaning) "正在清理..." else "正在检测...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate600
                )
            }
        }
    }

    if (showCleanupConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanupConfirm = false },
            title = { Text("确认清理", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "即将在 C3 设备上执行以下清理操作：",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Slate900
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CleanupItemDesc("🧠 释放内核缓存", "清除 page cache、dentry、inode 缓存，释放内存")
                        CleanupItemDesc("📁 删除过期日志", "删除 /data/log/ 下 7 天前的日志文件")
                        CleanupItemDesc("🗑 清理临时文件", "删除 /tmp/ 下 1 天前未访问的临时文件")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "此操作不会影响 openpilot 核心功能，仅清理可删除的缓存和过期数据。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanupConfirm = false
                        scope.launch {
                            isCleaning = true
                            runCleanupDetailed(sshManager) { cleanupResult = it }
                            isCleaning = false
                            showCleanupDialog = true
                            performCheck()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green500)
                ) {
                    Text("确认清理")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCleanupConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            title = { Text("清理结果", fontWeight = FontWeight.SemiBold) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(cleanupResult, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { showCleanupDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("日志导出") },
            text = { Text(exportResult) },
            confirmButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    if (showSuggestionDialog && selectedSuggestion != null) {
        val s = selectedSuggestion!!
        AlertDialog(
            onDismissRequest = { showSuggestionDialog = false },
            title = { Text(s.title, color = Slate900) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.description, style = MaterialTheme.typography.bodyLarge, color = Slate600)
                    HorizontalDivider(color = Slate200)
                    Text(s.detailText, style = MaterialTheme.typography.bodyMedium, color = Slate600)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSuggestionDialog = false
                        when (s.actionType) {
                            SuggestionAction.CHECK -> performCheck()
                            SuggestionAction.CLEANUP -> {
                                showCleanupConfirm = true
                            }
                            SuggestionAction.VIEW_LOGS -> {
                                scope.launch {
                                    val result = sshManager.executeCommand(
                                        "cat /data/community/crashes/error.log 2>/dev/null || echo '暂无错误日志'"
                                    )
                                    exportResult = "错误日志：\n${result.getOrElse { it.message ?: "读取失败" }}"
                                    showExportDialog = true
                                }
                            }
                            SuggestionAction.VIEW_SERVICES -> {
                                showServiceDetailDialog = true
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                ) {
                    Text(s.actionLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSuggestionDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // 服务详情弹窗 - 显示各关键服务的运行状态
    if (showServiceDetailDialog) {
        AlertDialog(
            onDismissRequest = { showServiceDetailDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (status.openpilotService) Icons.Default.CheckCircle else Icons.Default.Error,
                        null,
                        tint = if (status.openpilotService) Green500 else Red500,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "openpilot 服务状态",
                        color = Slate900,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 450.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 总览
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Slate100)
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "共检测 ${status.serviceDetails.size} 项服务",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate700
                        )
                        val abnormalCount = status.abnormalServices.size
                        if (abnormalCount > 0) {
                            Text(
                                "${abnormalCount} 项异常",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Red500,
                                fontWeight = FontWeight.Bold
                            )
                        } else {
                            Text(
                                "全部正常",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Green500,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 按分类显示服务状态
                    val categories = listOf("核心服务", "感知模型", "定位服务", "硬件通信", "日志与通信", "车机界面", "其他")
                    categories.forEach { category ->
                        val servicesInCategory = status.serviceDetails.filter { it.category == category }
                        if (servicesInCategory.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Slate500,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                servicesInCategory.forEach { svc ->
                                    ServiceStatusRow(serviceStatus = svc)
                                }
                            }
                        }
                    }

                    // 如果没有任何服务详情数据，显示提示
                    if (status.serviceDetails.isEmpty()) {
                        Text(
                            "暂无详细服务数据，请点击"立即体检"刷新状态。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate500
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showServiceDetailDialog = false
                        performCheck()
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                ) {
                    Text("重新检测")
                }
            },
            dismissButton = {
                TextButton(onClick = { showServiceDetailDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun HealthScoreCircle(score: Int) {
    val color = when {
        score >= 80 -> Green500
        score >= 60 -> Amber500
        else -> Red500
    }
    val sweepAngle = 360f * score / 100f

    Box(
        modifier = Modifier
            .size(140.dp)
            .drawBehind {
                drawArc(
                    color = Slate200,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                )
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    style = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = score.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = Slate900,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "健康分",
                style = MaterialTheme.typography.bodySmall,
                color = Slate600
            )
        }
    }
}

@Composable
private fun ActionChip(
    text: String,
    icon: ImageVector,
    color: androidx.compose.ui.graphics.Color,
    bgColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    icon: ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    bgColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        modifier = modifier
            .height(120.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelMedium, color = Slate600)
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = Slate900,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun StatusCard(
    label: String,
    value: String,
    isGood: Boolean,
    icon: ImageVector,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isGood) Green100 else Red100,
        modifier = modifier
            .height(130.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (isGood) Green500 else Red500, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelMedium, color = Slate600)
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isGood) Green500 else Red500)
                )
            }
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Slate900,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isGood) Green700 else Red600
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    description: String,
    icon: ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    iconBg: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    badge: String? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(24.dp))
                }
                if (badge != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Green100
                    ) {
                        Text(
                            text = badge,
                            color = Green500,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = Slate900,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600
            )
        }
    }
}

@Composable
private fun SuggestionItem(suggestion: Suggestion, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(suggestion.bgColor)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = suggestion.icon,
            contentDescription = null,
            tint = suggestion.iconColor,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suggestion.title,
                style = MaterialTheme.typography.titleMedium,
                color = Slate900,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = suggestion.description,
                style = MaterialTheme.typography.bodySmall,
                color = Slate600
            )
        }
        Text(
            text = "查看详情",
            style = MaterialTheme.typography.labelMedium,
            color = suggestion.iconColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private enum class SuggestionAction { CHECK, CLEANUP, VIEW_LOGS, VIEW_SERVICES }

private data class Suggestion(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val iconColor: androidx.compose.ui.graphics.Color,
    val bgColor: androidx.compose.ui.graphics.Color,
    val detailText: String = "",
    val actionType: SuggestionAction = SuggestionAction.CHECK,
    val actionLabel: String = "立即体检"
)

private fun generateSuggestions(status: DeviceStatus, healthScore: Int): List<Suggestion> {
    val list = mutableListOf<Suggestion>()

    // ===== 优先：服务状态异常检测 =====
    val abnormalCount = status.abnormalServices.size
    val hasPandaIssue = !status.pandaComm

    if (abnormalCount > 0 || hasPandaIssue) {
        val abnormalNames = status.abnormalServiceNames
        val issues = buildString {
            if (!status.openpilotService) append("· openpilot manager 进程未运行，系统可能处于离线状态\n")
            if (abnormalCount > 0) {
                append("· 以下关键服务未运行：${abnormalNames}\n")
            }
            if (hasPandaIssue) {
                append("· Panda 通信异常：未检测到 Panda 设备连接\n")
            }
        }
        list.add(
            Suggestion(
                title = if (abnormalCount > 0) "设备监控异常（${abnormalCount}项服务停止）" else "设备通信异常",
                description = if (abnormalCount > 0 && hasPandaIssue)
                    "openpilot 关键服务异常且 Panda 通信中断，自动驾驶功能可能受限。"
                else if (abnormalCount > 0)
                    "${abnormalNames} 等关键服务未运行，自动驾驶功能可能受限。"
                else
                    "Panda 通信异常，无法与车辆 CAN 总线通信。",
                icon = Icons.Default.Warning,
                iconColor = Red500,
                bgColor = Red100,
                detailText = buildString {
                    append("检测到以下异常：\n\n")
                    append(issues)
                    append("\n可能原因：\n")
                    append("1) openpilot 启动失败或进程异常退出\n")
                    append("2) 近期升级/修改参数导致 manager 未正确重启\n")
                    append("3) Panda 设备 USB 连接松动或供电异常\n")
                    append("4) 关键依赖（如 camera、model）初始化失败\n")
                    append("\n建议：点击下方按钮查看详细服务状态，定位具体异常服务后尝试重启 openpilot。")
                },
                actionType = SuggestionAction.VIEW_SERVICES,
                actionLabel = "查看服务详情"
            )
        )
    }

    when {
        healthScore < 60 -> list.add(
            Suggestion(
                "设备健康度较低",
                "检测到多项指标异常，建议立即体检并处理异常项。健康分 ${healthScore}/100",
                Icons.Default.Warning,
                Red500, Red100,
                detailText = "健康分仅 ${healthScore}/100。请点击下方按钮进行全面体检，查看 CPU 温度、内存占用、存储空间及服务运行状态等详细指标。",
                actionType = SuggestionAction.CHECK,
                actionLabel = "立即体检"
            )
        )
        healthScore < 80 -> list.add(
            Suggestion(
                "建议定期清理缓存",
                "清理系统无关日志以节省空间。当前存储剩余 ${status.storageFree}。",
                Icons.Default.CleaningServices,
                Amber500, Amber100,
                detailText = "健康分 ${healthScore}/100，存在优化空间。系统缓存、过期日志会占用存储空间并拖慢系统响应速度。点击下方按钮执行一键清理。\n\n当前存储：${status.storageFree}\n内存占用：${status.memoryUsage}%\nCPU 温度：${status.cpuTemp}°C",
                actionType = SuggestionAction.CLEANUP,
                actionLabel = "一键清理"
            )
        )
        else -> list.add(
            Suggestion(
                "未发现关键服务异常",
                "openpilot 服务与 Panda 通信均正常。",
                Icons.Default.CheckCircle,
                Green500, Green100,
                detailText = "健康分 ${healthScore}/100，各项指标正常。openpilot 关键服务全部运行中，设备状态良好。建议定期进行体检以保持最佳性能。",
                actionType = SuggestionAction.CHECK,
                actionLabel = "重新体检"
            )
        )
    }

    when {
        status.memoryUsage > 85 -> list.add(
            Suggestion(
                "内存占用较高",
                "当前内存占用 ${status.memoryUsage}%，建议清理后台或释放缓存。",
                Icons.Default.Memory,
                Red500, Red100,
                detailText = "内存占用已达到 ${status.memoryUsage}%，接近满载。高内存占用会导致 openpilot 进程响应变慢，甚至触发 OOM (Out of Memory) 强制终止关键进程。\n\n建议立即执行垃圾清理，释放系统缓存和过期日志文件。",
                actionType = SuggestionAction.CLEANUP,
                actionLabel = "释放内存"
            )
        )
        status.memoryUsage > 70 -> list.add(
            Suggestion(
                "建议适度清理缓存",
                "内存占用 ${status.memoryUsage}%，建议定期释放系统缓存以保持流畅。",
                Icons.Default.Storage,
                Amber500, Amber100,
                detailText = "内存占用 ${status.memoryUsage}%，处于中等水平。虽然当前运行稳定，但建议定期清理防止积累到高水位。点击下方按钮执行清理。",
                actionType = SuggestionAction.CLEANUP,
                actionLabel = "清理缓存"
            )
        )
        else -> list.add(
            Suggestion(
                "当前空间充足",
                "内存占用 ${status.memoryUsage}%，运行空间充裕。",
                Icons.Default.Storage,
                Blue500, Blue100,
                detailText = "内存占用仅 ${status.memoryUsage}%，系统有充足的运行空间，openpilot 各项功能可以顺畅运行。无需特别操作。",
                actionType = SuggestionAction.CHECK,
                actionLabel = "查看详情"
            )
        )
    }

    when {
        status.cpuTemp > 80 -> list.add(
            Suggestion(
                "设备温度过高",
                "当前 ${status.cpuTemp}°C，系统散热可能不足，建议停车降温。",
                Icons.Default.Thermostat,
                Red500, Red100,
                detailText = "CPU 温度 ${status.cpuTemp}°C，已超过安全阈值 (80°C)。高温会触发 CPU 降频保护，导致 openpilot 处理能力下降，影响自动驾驶性能。\n\n建议：\n1. 将车辆停放在阴凉处\n2. 确认 C3 散热风扇正常工作\n3. 暂停使用非必要的后台功能",
                actionType = SuggestionAction.CLEANUP,
                actionLabel = "清理降温"
            )
        )
        status.cpuTemp > 70 -> list.add(
            Suggestion(
                "设备温度略高",
                "当前 ${status.cpuTemp}°C，温度偏高但仍在安全范围。",
                Icons.Default.Thermostat,
                Amber500, Amber100,
                detailText = "CPU 温度 ${status.cpuTemp}°C，处于偏高区间。虽然尚未触发降频保护，但建议注意通风散热，避免持续高温运行。\n\n可通过清理内存和缓存进程来降低 CPU 负载，从而辅助降温。",
                actionType = SuggestionAction.CHECK,
                actionLabel = "查看状态"
            )
        )
        else -> list.add(
            Suggestion(
                "设备温度正常",
                "当前 ${status.cpuTemp}°C，系统散热平稳，继续保持。",
                Icons.Default.Thermostat,
                Green500, Green100,
                detailText = "CPU 温度 ${status.cpuTemp}°C，处于最佳工作区间。散热系统运行正常，openpilot 可在全性能模式下运行。",
                actionType = SuggestionAction.CHECK,
                actionLabel = "查看详情"
            )
        )
    }

    return list
}

private fun calculateHealthScore(status: DeviceStatus): Pair<Int, String> {
    var score = 0
    score += when {
        status.cpuTemp < 60 -> 25
        status.cpuTemp < 70 -> 15
        status.cpuTemp < 80 -> 5
        else -> 0
    }
    score += when {
        status.memoryUsage < 60 -> 25
        status.memoryUsage < 75 -> 15
        status.memoryUsage < 90 -> 5
        else -> 0
    }
    val freeGB = status.storageFree.replace("G", "").toFloatOrNull() ?: 100f
    score += when {
        freeGB > 20 -> 25
        freeGB > 10 -> 15
        freeGB > 5 -> 5
        else -> 0
    }
    score += if (status.openpilotService && status.pandaComm) 25 else 0

    val label = when {
        score >= 90 -> "设备体态良好"
        score >= 70 -> "设备状态一般"
        score >= 50 -> "建议优化"
        else -> "设备状态较差"
    }
    return score to label
}

@Composable
private fun CleanupItemDesc(title: String, desc: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Slate100)
            .padding(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Slate900, fontWeight = FontWeight.SemiBold)
        Text(desc, style = MaterialTheme.typography.bodySmall, color = Slate600)
    }
}

@Composable
private fun ServiceStatusRow(serviceStatus: ServiceStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (serviceStatus.running) Green50 else Red50)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 状态指示灯
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (serviceStatus.running) Green500 else Red500)
        )
        // 服务名称
        Text(
            text = serviceStatus.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = Slate900,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        // 运行状态文字
        Text(
            text = if (serviceStatus.running) "运行中" else "已停止",
            style = MaterialTheme.typography.bodySmall,
            color = if (serviceStatus.running) Green600 else Red600,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private suspend fun runCleanupDetailed(sshManager: SshManager, onResult: (String) -> Unit) {
    val script = """
echo "===== 清理前状态 ====="
BEFORE_MEM=${'$'}(free -m | grep Mem | awk '{print ${'$'}3}')
echo "内存使用: ${'$'}BEFORE_MEM MB / ${'$'}(free -m | grep Mem | awk '{print ${'$'}2}') MB"
echo "过期日志文件: ${'$'}(find /data/log -maxdepth 1 -type f -mtime +7 2>/dev/null | wc -l) 个"
echo "临时文件: ${'$'}(find /tmp -type f -atime +1 2>/dev/null | wc -l) 个"
echo ""
echo "===== 执行清理 ====="
echo " [1/3] 释放内存缓存..."
echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null && echo "  > 内核缓存释放完成" || echo "  > 释放失败（可能无 sudo 权限）"
echo " [2/3] 删除过期日志（7天前）..."
find /data/log -maxdepth 1 -type f -mtime +7 -delete 2>/dev/null && echo "  > 过期日志已删除" || echo "  > 无过期日志，跳过"
echo " [3/3] 清理临时文件（1天前未访问）..."
find /tmp -type f -atime +1 -delete 2>/dev/null && echo "  > 临时文件已删除" || echo "  > 无临时文件，跳过"
echo ""
echo "===== 清理后状态 ====="
AFTER_MEM=${'$'}(free -m | grep Mem | awk '{print ${'$'}3}')
echo "内存使用: ${'$'}AFTER_MEM MB / ${'$'}(free -m | grep Mem | awk '{print ${'$'}2}') MB"
FREED=${'$'}((${'$'}{BEFORE_MEM:-0} - ${'$'}{AFTER_MEM:-0}))
echo "释放内存: ${'$'}FREED MB"
echo "剩余过期日志: ${'$'}(find /data/log -maxdepth 1 -type f -mtime +7 2>/dev/null | wc -l) 个"
echo "剩余临时文件: ${'$'}(find /tmp -type f -atime +1 2>/dev/null | wc -l) 个"
    """.trimIndent()
    sshManager.executeCommand(script).fold(
        onSuccess = { onResult(it) },
        onFailure = { onResult("清理失败：${it.message}") }
    )
}

private suspend fun runCleanup(sshManager: SshManager, onResult: (String) -> Unit) {
    runCleanupDetailed(sshManager, onResult)
}

private suspend fun exportLogs(sshManager: SshManager, onResult: (String) -> Unit) {
    val timestamp = System.currentTimeMillis() / 1000
    val path = "/data/log/export_${timestamp}.tar.gz"
    sshManager.executeCommand("tar -czf $path /data/log /data/community/crashes 2>/dev/null || tar -czf $path /data/log 2>/dev/null").fold(
        onSuccess = { onResult("日志已导出到 C3：$path") },
        onFailure = { onResult("日志导出失败：${it.message}") }
    )
}

private suspend fun refreshStatus(sshManager: SshManager, onResult: (DeviceStatus) -> Unit) {
    val result = sshManager.getDeviceStatus()
    result.onSuccess { data ->
        val memoryParts = (data["memory"] ?: "0 1").split(" ")
        val used = memoryParts.firstOrNull()?.toIntOrNull() ?: 0
        val total = memoryParts.getOrNull(1)?.toIntOrNull() ?: 1
        val memoryUsage = if (total > 0) (used * 100 / total) else 0

        // 解析详细服务状态
        val serviceDetails = parseServiceDetails(data["serviceDetails"] ?: "")
        // manager 进程是否运行（通过 serviceDetails 中的 manager 判断，兼容旧数据）
        val managerRunning = serviceDetails.any { it.name == "manager" && it.running }

        onResult(
            DeviceStatus(
                name = data["hostname"] ?: "Comma C3",
                hardware = data["hardware"] ?: "comma three",
                cpuTemp = data["cpuTemp"]?.toFloatOrNull()?.div(1000f) ?: 0f,
                deviceTemp = data["deviceTemp"]?.toFloatOrNull()?.div(1000f) ?: 0f,
                bmsTemp = data["bmsTemp"]?.toFloatOrNull()?.div(1000f) ?: 0f,
                cpuLoad = data["cpuLoad"]?.toFloatOrNull() ?: 0f,
                memoryUsage = memoryUsage,
                storageFree = data["storageFree"] ?: "--",
                storageFreeSsd = data["storageFreeSsd"] ?: "--",
                serial = data["serial"] ?: "unknown",
                stableId = data["dongleId"] ?: "unknown",
                isConnected = true,
                openpilotService = managerRunning || (data["openpilotProcesses"]?.toIntOrNull() ?: 0) > 0,
                pandaComm = (data["pandaComm"]?.toIntOrNull() ?: 0) > 0,
                serviceDetails = serviceDetails
            )
        )
    }.onFailure {
        onResult(DeviceStatus(isConnected = false))
    }
}

/**
 * 解析 SSH 返回的服务检测原始文本，构建 ServiceStatus 列表。
 * 输入格式（每行）: "manager|进程管家:1" 或 "modeld|AI模型(modeld):0"
 */
private fun parseServiceDetails(raw: String): List<ServiceStatus> {
    if (raw.isBlank() || raw == "{}") return emptyList()

    // 定义各服务的归属分类
    val coreServices = setOf("manager", "selfdrived", "controlsd", "plannerd")
    val perceptionServices = setOf("camerad", "modeld", "modeld_snpe", "modeld_tinygrad", "dmonitoringd")
    val localizationServices = setOf("locationd", "calibrationd", "paramsd")
    val hardwareServices = setOf("pandad", "hardwared", "sensord")
    val logServices = setOf("loggerd", "uploader", "athenad", "deleter")
    val uiServices = setOf("ui")

    fun categorize(name: String): String = when (name) {
        in coreServices -> "核心服务"
        in perceptionServices -> "感知模型"
        in localizationServices -> "定位服务"
        in hardwareServices -> "硬件通信"
        in logServices -> "日志与通信"
        in uiServices -> "车机界面"
        else -> "其他"
    }

    return raw.lines()
        .filter { it.contains("|") && it.contains(":") }
        .mapNotNull { line ->
            // 格式: "manager|进程管家:1"
            val pipeIdx = line.lastIndexOf('|')
            val colonIdx = line.lastIndexOf(':')
            if (pipeIdx < 0 || colonIdx < 0 || colonIdx <= pipeIdx) return@mapNotNull null

            val procName = line.substring(0, pipeIdx).trim()
            val displayName = line.substring(pipeIdx + 1, colonIdx).trim()
            val count = line.substring(colonIdx + 1).trim().toIntOrNull() ?: 0

            ServiceStatus(
                name = procName,
                displayName = displayName.ifEmpty { procName },
                running = count > 0,
                shouldBeRunning = true,
                category = categorize(procName)
            )
        }
}
