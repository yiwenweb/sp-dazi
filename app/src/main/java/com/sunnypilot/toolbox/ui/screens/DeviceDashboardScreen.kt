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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.DeviceStatus
import com.sunnypilot.toolbox.ui.components.StatusChip
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DeviceDashboardScreen(
    sshManager: SshManager,
    onDisconnected: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var status by remember { mutableStateOf(DeviceStatus()) }
    var isLoading by remember { mutableStateOf(false) }
    var isOfflineMode by remember { mutableStateOf(false) }
    var showRebootDialog by remember { mutableStateOf(false) }
    var showShutdownDialog by remember { mutableStateOf(false) }
    var showErrorLog by remember { mutableStateOf(false) }
    var errorLogText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refreshOfflineState() {
        scope.launch {
            sshManager.executeCommand(
                "python -c \"from openpilot.common.params import Params; print(Params().get_bool('Offroad'))\""
            ).onSuccess { output ->
                isOfflineMode = output.trim() == "True"
            }
        }
    }

    LaunchedEffect(sshManager.isConnected()) {
        if (sshManager.isConnected()) {
            refreshStatus(sshManager) { status = it }
            refreshOfflineState()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 设备主控台
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .padding(28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 左侧：标题 + 信息卡
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Teal500)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "设备主控台",
                            style = MaterialTheme.typography.labelLarge,
                            color = Teal500
                        )
                    }

                    Text(
                        text = "${status.name} · ${status.hardware} · ${status.software}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Slate900
                    )

                    Text(
                        text = "这里会集中展示硬件身份、CPU、温度、运行软件和稳定激活标识，适合做车机端设备总览。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate600
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusChip("已连接", status.isConnected)
                        StatusChip("Wi-Fi", true)
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Blue100
                        ) {
                            Text(
                                text = "密码登录",
                                color = Blue500,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    // 2x2 信息卡
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoCard(
                                label = "硬件",
                                value = status.hardware,
                                icon = Icons.Default.Devices,
                                iconColor = Blue500,
                                bgColor = Blue100,
                                modifier = Modifier.weight(1f).height(120.dp)
                            )
                            InfoCard(
                                label = "CPU",
                                value = "AArch64 Processor",
                                icon = Icons.Default.Memory,
                                iconColor = Teal500,
                                bgColor = Teal50,
                                modifier = Modifier.weight(1f).height(120.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoCard(
                                label = "CPU 温度",
                                value = "${status.cpuTemp}°C",
                                icon = Icons.Default.Thermostat,
                                iconColor = Amber500,
                                bgColor = Amber100,
                                modifier = Modifier.weight(1f).height(120.dp)
                            )
                            InfoCard(
                                label = "设备温度",
                                value = "${status.deviceTemp}°C",
                                icon = Icons.Default.Thermostat,
                                iconColor = Green500,
                                bgColor = Green100,
                                modifier = Modifier.weight(1f).height(120.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(28.dp))

                // 右侧：设备信息 + 操作按钮
                Column(
                    modifier = Modifier.width(280.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = status.stableId,
                            style = MaterialTheme.typography.titleMedium,
                            color = Slate900,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = status.ipAddress.ifEmpty { "--" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate600
                        )
                        Text(
                            text = status.serial,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Slate600
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton(
                                text = "重启",
                                color = Teal500,
                                icon = Icons.Default.Refresh,
                                modifier = Modifier.weight(1f),
                                onClick = { showRebootDialog = true }
                            )
                            ActionButton(
                                text = "关机",
                                color = Red500,
                                icon = Icons.Default.PowerSettingsNew,
                                modifier = Modifier.weight(1f),
                                onClick = { showShutdownDialog = true }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton(
                                text = if (isOfflineMode) "恢复正常" else "离线模式",
                                color = if (isOfflineMode) Red500 else Slate600,
                                icon = if (isOfflineMode) Icons.Default.AirplanemodeActive else Icons.Default.LinkOff,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        // 使用 Offroad 参数控制离线模式
                                        val cmd = if (isOfflineMode) {
                                            // 恢复正常：删除 Offroad 参数，重启 openpilot
                                            "python -c \"from openpilot.common.params import Params; Params().remove('Offroad')\" && sudo systemctl restart comma"
                                        } else {
                                            // 进入离线模式：设置 Offroad 参数，重启 openpilot
                                            "python -c \"from openpilot.common.params import Params; Params().put_bool('Offroad', True)\" && sudo systemctl restart comma"
                                        }
                                        sshManager.executeCommand(cmd).onSuccess {
                                            // 等待服务重启后刷新状态
                                            kotlinx.coroutines.delay(3000)
                                            refreshOfflineState()
                                        }
                                    }
                                }
                            )
                            ActionButton(
                                text = "错误日志",
                                color = Blue500,
                                icon = Icons.Default.Description,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    scope.launch {
                                        val cmds = listOf(
                                            "echo '===== 崩溃日志 (error.log) ====='",
                                            "cat /data/community/crashes/error.log 2>/dev/null || echo '(无崩溃日志)'",
                                            "",
                                            "echo '===== 历史崩溃记录 ====='",
                                            "ls -lt /data/community/crashes/*.log 2>/dev/null | head -5 || echo '(无历史崩溃记录)'",
                                            "",
                                            "echo '===== 最近运行时错误 (swaglog) ====='",
                                            "for f in /data/log/swaglog.*; do tail -20 \"\$f\" 2>/dev/null; done | grep -i 'error\\|crash\\|traceback\\|exception' | tail -30 || echo '(无运行时错误)'",
                                            "",
                                            "echo '===== 系统日志错误 ====='",
                                            "journalctl -n 30 --no-pager 2>/dev/null | grep -i 'error\\|crash\\|traceback' | tail -10 || echo '(无法读取系统日志)'"
                                        )
                                        errorLogText = sshManager.executeCommand(
                                            cmds.joinToString("\n")
                                        ).getOrElse { "读取失败: ${it.message}" }
                                        showErrorLog = true
                                    }
                                }
                            )
                        }
                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    refreshStatus(sshManager) { status = it }
                                    refreshOfflineState()
                                    isLoading = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isLoading) "刷新中" else "刷新状态")
                        }
                    }
                }
            }
        }

        // 运行状态
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .padding(28.dp)
        ) {
            Text(
                text = "运行状态",
                style = MaterialTheme.typography.headlineMedium,
                color = Slate900
            )
            Text(
                text = "快速查看 CPU、温度、内存和设备当前负载。",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600
            )

            Spacer(modifier = Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCard(
                        label = "CPU 温度",
                        value = "${status.cpuTemp}°C",
                        icon = Icons.Default.Thermostat,
                        iconColor = Amber500,
                        bgColor = Amber100,
                        modifier = Modifier.weight(1f).height(120.dp)
                    )
                    InfoCard(
                        label = "CPU 负载",
                        value = "${status.cpuLoad}",
                        icon = Icons.Default.Speed,
                        iconColor = Purple500,
                        bgColor = Purple100,
                        modifier = Modifier.weight(1f).height(120.dp)
                    )
                    InfoCard(
                        label = "内存使用",
                        value = "${status.memoryUsage}%",
                        icon = Icons.Default.Memory,
                        iconColor = Blue500,
                        bgColor = Blue100,
                        modifier = Modifier.weight(1f).height(120.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoCard(
                        label = "设备温度",
                        value = "${status.deviceTemp}°C",
                        icon = Icons.Default.Thermostat,
                        iconColor = Green500,
                        bgColor = Green100,
                        modifier = Modifier.weight(1f).height(120.dp)
                    )
                    InfoCard(
                        label = "BMS 温度",
                        value = "${status.bmsTemp}°C",
                        icon = Icons.Default.BatteryFull,
                        iconColor = Teal500,
                        bgColor = Teal50,
                        modifier = Modifier.weight(1f).height(120.dp)
                    )
                    InfoCard(
                        label = "存储剩余",
                        value = status.storageFree,
                        icon = Icons.Default.Storage,
                        iconColor = Slate600,
                        bgColor = Slate200,
                        modifier = Modifier.weight(1f).height(120.dp)
                    )
                }
            }
        }
    }

    if (showRebootDialog) {
        ConfirmDialog(
            title = "确认重启",
            text = "确定要立即重启 C3 吗？\n\n将先停止 openpilot 服务再重启系统。",
            confirm = "重启",
            onConfirm = {
                showRebootDialog = false
                scope.launch {
                    // 优雅关闭 openpilot 再重启
                    sshManager.executeCommand("sudo systemctl stop comma; sleep 2; sudo reboot")
                }
            },
            onDismiss = { showRebootDialog = false }
        )
    }

    if (showShutdownDialog) {
        ConfirmDialog(
            title = "确认关机",
            text = "确定要立即关闭 C3 吗？\n\n将先停止 openpilot 服务再关机。",
            confirm = "关机",
            onConfirm = {
                showShutdownDialog = false
                scope.launch {
                    // 优雅关闭 openpilot 再关机
                    sshManager.executeCommand("sudo systemctl stop comma; sleep 2; sudo poweroff")
                    // 等待命令发送完成后再断开连接
                    kotlinx.coroutines.delay(500)
                    sshManager.disconnect()
                    onDisconnected()
                }
            },
            onDismiss = { showShutdownDialog = false }
        )
    }

    if (showErrorLog) {
        AlertDialog(
            onDismissRequest = { showErrorLog = false },
            title = { Text("错误日志") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(errorLogText)
                }
            },
            confirmButton = {
                TextButton(onClick = { showErrorLog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Red500)
            ) {
                Text(confirm)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ActionButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
        modifier = modifier
            .height(48.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
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
private fun RowScope.InfoCard(
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
                style = MaterialTheme.typography.titleLarge,
                color = Slate900,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
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
