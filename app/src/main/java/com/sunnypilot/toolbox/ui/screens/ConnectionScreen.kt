package com.sunnypilot.toolbox.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ConnectionScreen(
    sshManager: SshManager,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier
) {
    var host by remember { mutableStateOf("192.168.43.1") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf(SshManager.DEFAULT_USER) }
    var password by remember { mutableStateOf("") }
    var selectedAuth by remember { mutableStateOf(0) }
    var isConnecting by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("等待连接") }
    val scope = rememberCoroutineScope()

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 左侧：SSH 连接表单
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .padding(28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SSH 连接",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Slate900
                )
                Button(
                    onClick = { /* 自动发现 */ },
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("自动发现")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Amber100,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "不知道如何填写 IP 和端口？点击右上角「自动发现」一键扫描局域网设备",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Amber500,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "支持密码、PPK 私钥和文件私钥三种登录方式。",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host / IP") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(0.4f)
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(0.6f)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "登录认证方式",
                style = MaterialTheme.typography.titleMedium,
                color = Slate900
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AuthTab("密码登录", selectedAuth == 0) { selectedAuth = 0 }
                AuthTab("PPK 私钥", selectedAuth == 1) { selectedAuth = 1 }
                AuthTab("文件私钥", selectedAuth == 2) { selectedAuth = 2 }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedAuth == 0) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Slate100,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("点击选择私钥文件", color = Slate400)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    scope.launch {
                        isConnecting = true
                        statusText = "正在连接..."
                        val result = sshManager.connectWithPassword(
                            host = host,
                            port = port.toIntOrNull() ?: 22,
                            username = username,
                            password = password
                        )
                        result.onSuccess {
                            statusText = "连接成功"
                            onConnected()
                        }.onFailure {
                            statusText = "连接失败: ${it.message}"
                        }
                        isConnecting = false
                    }
                },
                enabled = !isConnecting,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(if (isConnecting) "连接中..." else "连接设备")
            }
        }

        // 右侧：连接状态和设备配置
        Column(
            modifier = Modifier
                .weight(0.7f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatusCard(title = "连接状态", content = statusText)
            StatusCard(
                title = "握手过程",
                content = "开始连接后将在此动态展示表单校验、密钥装载、握手及认证阶段。"
            )
            StatusCard(
                title = "设备配置",
                content = "保存的设备配置将显示在这里，可快速载入。"
            )
        }
    }
}

@Composable
private fun AuthTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) Teal500 else Color.White,
        shadowElevation = if (selected) 0.dp else 2.dp,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White else Slate600,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun StatusCard(title: String, content: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Panel)
            .padding(24.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = Slate900
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = content,
            style = MaterialTheme.typography.bodyLarge,
            color = Slate600
        )
    }
}
