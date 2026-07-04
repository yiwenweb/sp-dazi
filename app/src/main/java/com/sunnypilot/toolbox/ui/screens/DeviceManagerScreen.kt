package com.sunnypilot.toolbox.ui.screens

import androidx.compose.foundation.background
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
    var showCleanupDialog by remember { mutableStateOf(false) }
    var cleanupResult by remember { mutableStateOf("") }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf("") }
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
                        label = "存储剩余",
                        value = status.storageFree,
                        icon = Icons.Default.Storage,
                        iconColor = Teal500,
                        bgColor = Teal50,
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
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        label = "Panda 通信",
                        value = if (status.pandaComm) "正常" else "异常",
                        isGood = status.pandaComm,
                        icon = if (status.pandaComm) Icons.Default.CheckCircle else Icons.Default.Error,
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
                        onClick = {
                            scope.launch {
                                runCleanup(sshManager) { cleanupResult = it }
                                showCleanupDialog = true
                                performCheck()
                            }
                        }
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
                        SuggestionItem(suggestion = suggestion)
                    }
                }
            }
        }
    }

    if (isChecking) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Teal500)
        }
    }

    if (showCleanupDialog) {
        AlertDialog(
            onDismissRequest = { showCleanupDialog = false },
            title = { Text("清理结果") },
            text = { Text(cleanupResult) },
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
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isGood) Green100 else Red100,
        modifier = modifier
            .height(120.dp)
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
private fun SuggestionItem(suggestion: Suggestion) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(suggestion.bgColor)
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

private data class Suggestion(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val iconColor: androidx.compose.ui.graphics.Color,
    val bgColor: androidx.compose.ui.graphics.Color
)

private fun generateSuggestions(status: DeviceStatus, healthScore: Int): List<Suggestion> {
    val list = mutableListOf<Suggestion>()
    when {
        healthScore < 60 -> list.add(
            Suggestion(
                "设备健康度较低",
                "检测到多项指标异常，建议立即体检并处理异常项。",
                Icons.Default.Warning,
                Red500,
                Red100
            )
        )
        healthScore < 80 -> list.add(
            Suggestion(
                "建议定期清理缓存",
                "清理系统无关日志以节省空间。当前存储剩余 ${status.storageFree}。",
                Icons.Default.CleaningServices,
                Amber500,
                Amber100
            )
        )
        else -> list.add(
            Suggestion(
                "未发现关键服务异常",
                "openpilot 服务与 Panda 通信均正常。",
                Icons.Default.CheckCircle,
                Green500,
                Green100
            )
        )
    }

    when {
        status.memoryUsage > 85 -> list.add(
            Suggestion(
                "内存占用较高",
                "当前内存占用 ${status.memoryUsage}%，建议清理后台或释放缓存。",
                Icons.Default.Memory,
                Red500,
                Red100
            )
        )
        status.memoryUsage > 70 -> list.add(
            Suggestion(
                "当前空间充足",
                "内存占用 ${status.memoryUsage}%，运行空间充裕。",
                Icons.Default.Storage,
                Blue500,
                Blue100
            )
        )
        else -> list.add(
            Suggestion(
                "当前空间充足",
                "内存占用 ${status.memoryUsage}%，运行空间充裕。",
                Icons.Default.Storage,
                Blue500,
                Blue100
            )
        )
    }

    when {
        status.cpuTemp > 80 -> list.add(
            Suggestion(
                "设备温度过高",
                "当前 ${status.cpuTemp}°C，系统散热可能不足，建议停车降温。",
                Icons.Default.Thermostat,
                Red500,
                Red100
            )
        )
        status.cpuTemp > 70 -> list.add(
            Suggestion(
                "设备温度正常",
                "当前 ${status.cpuTemp}°C，系统散热平稳，继续保持。",
                Icons.Default.Thermostat,
                Amber500,
                Amber100
            )
        )
        else -> list.add(
            Suggestion(
                "设备温度正常",
                "当前 ${status.cpuTemp}°C，系统散热平稳，继续保持。",
                Icons.Default.Thermostat,
                Green500,
                Green100
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

private suspend fun runCleanup(sshManager: SshManager, onResult: (String) -> Unit) {
    val commands = listOf(
        "echo 3 | sudo tee /proc/sys/vm/drop_caches > /dev/null",
        "find /data/log -maxdepth 1 -type f -mtime +7 -delete 2>/dev/null || true",
        "find /tmp -type f -atime +1 -delete 2>/dev/null || true"
    ).joinToString("; ")
    sshManager.executeCommand(commands).fold(
        onSuccess = { onResult("清理完成：系统缓存已释放，过期日志已清理。") },
        onFailure = { onResult("清理失败：${it.message}") }
    )
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
                serial = data["serial"] ?: "unknown",
                stableId = data["dongleId"] ?: "unknown",
                isConnected = true,
                openpilotService = (data["openpilotProcesses"]?.toIntOrNull() ?: 0) > 0,
                pandaComm = true
            )
        )
    }.onFailure {
        onResult(DeviceStatus(isConnected = false))
    }
}
