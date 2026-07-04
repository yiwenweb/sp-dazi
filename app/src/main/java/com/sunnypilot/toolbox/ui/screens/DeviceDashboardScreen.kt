package com.sunnypilot.toolbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.DeviceStatus
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DeviceDashboardScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    var status by remember { mutableStateOf(DeviceStatus()) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sshManager.isConnected()) {
        if (sshManager.isConnected()) {
            refreshStatus(sshManager) { status = it }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 设备主控台大卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .padding(28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${status.name} · ${status.hardware} · ${status.software}",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "这里会集中展示硬件身份、CPU、温度、运行软件和稳定激活标识，适合做车机端设备总览。",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Slate600
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusChip("已连接", status.isConnected)
                        StatusChip("Wi-Fi", true)
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Blue100
                        ) {
                            Text(
                                text = "密码登录",
                                color = Blue500,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.width(280.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoRow("稳定激活 ID", status.stableId)
                    InfoRow("硬件串号", status.serial)
                    InfoRow("连接地址", status.ipAddress)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("重启", Teal500)
                        ActionButton("关机", Red500)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("强制下线", Slate600)
                        ActionButton("错误日志", Amber500)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoCard("硬件", status.hardware, Icons.Default.Devices, Blue500, Blue100)
                InfoCard("CPU", "AArch64 Processor", Icons.Default.Memory, Teal500, Teal50)
                InfoCard("CPU 温度", "${status.cpuTemp}°C", Icons.Default.Thermostat, Amber500, Amber100)
                InfoCard("设备温度", "${status.deviceTemp}°C", Icons.Default.Thermostat, Green500, Green100)
            }
        }

        // 运行状态
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .padding(28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "运行状态",
                        style = MaterialTheme.typography.headlineLarge,
                        color = Slate900
                    )
                    Text(
                        text = "快速查看 CPU、温度、内存和设备当前负载。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate600
                    )
                }
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            refreshStatus(sshManager) { status = it }
                            isLoading = false
                        }
                    },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                ) {
                    Text(if (isLoading) "刷新中" else "刷新状态")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoCard("CPU 温度", "${status.cpuTemp}°C", Icons.Default.Thermostat, Amber500, Amber100)
                InfoCard("设备温度", "${status.deviceTemp}°C", Icons.Default.Thermostat, Green500, Green100)
                InfoCard("内存占用", "${status.memoryUsage}%", Icons.Default.Memory, Blue500, Blue100)
                InfoCard(
                    "openpilot 服务",
                    if (status.openpilotService) "正常" else "异常",
                    Icons.Default.CheckCircle,
                    if (status.openpilotService) Green500 else Red500,
                    if (status.openpilotService) Green100 else Red100
                )
                InfoCard(
                    "Panda 通信",
                    if (status.pandaComm) "正常" else "异常",
                    Icons.Default.Usb,
                    if (status.pandaComm) Green500 else Red500,
                    if (status.pandaComm) Green100 else Red100
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.End
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Slate600)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, color = Slate900)
    }
}

@Composable
private fun ActionButton(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f),
        modifier = Modifier.clickable {}
    ) {
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun RowScope.InfoCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: androidx.compose.ui.graphics.Color,
    bgColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1.6f)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = Slate600)
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
