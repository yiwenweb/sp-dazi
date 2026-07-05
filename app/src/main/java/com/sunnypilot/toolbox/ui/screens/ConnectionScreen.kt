package com.sunnypilot.toolbox.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.data.ConnectionConfigRepository
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.AuthType
import com.sunnypilot.toolbox.model.ConnectionConfig
import com.sunnypilot.toolbox.model.ConnectionStage
import com.sunnypilot.toolbox.network.AutoDiscovery
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ConnectionScreen(
    sshManager: SshManager,
    repository: ConnectionConfigRepository,
    onConnected: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stage by sshManager.connectionStage.collectAsState()

    var host by remember { mutableStateOf("192.168.43.1") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf(SshManager.DEFAULT_USER) }
    var password by remember { mutableStateOf("") }
    var privateKeyText by remember { mutableStateOf("") }
    var selectedAuth by remember { mutableStateOf(AuthType.PASSWORD) }
    var savedKeyFileName by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("等待连接") }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoveredHosts by remember { mutableStateOf(listOf<AutoDiscovery.DiscoveredHost>()) }
    var showDiscoveryDialog by remember { mutableStateOf(false) }
    var discoveryProgress by remember { mutableStateOf(0 to 0) }
    var hasLoadedConfig by remember { mutableStateOf(false) }

    fun loadDefaultPrivateKey(): String {
        return try {
            context.assets.open("menmen.ppk").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            ""
        }
    }

    // Load saved config
    LaunchedEffect(Unit) {
        repository.configFlow.collect { config ->
            if (!hasLoadedConfig) {
                host = config.host.ifBlank { "192.168.43.1" }
                port = config.port.toString()
                username = config.username
                selectedAuth = config.authType
                password = config.password
                privateKeyText = config.privateKeyContent.ifBlank {
                    loadDefaultPrivateKey().also { key ->
                        if (key.isNotBlank()) savedKeyFileName = "menmen.ppk"
                    }
                }
                savedKeyFileName = config.savedKeyFileName.ifBlank {
                    if (privateKeyText.isNotBlank()) "menmen.ppk" else ""
                }
                hasLoadedConfig = true
            }
        }
    }

    // Stage text
    LaunchedEffect(stage) {
        statusText = when (stage) {
            ConnectionStage.IDLE -> if (sshManager.isConnected()) "已连接" else "等待连接"
            ConnectionStage.RESOLVING -> "正在解析地址..."
            ConnectionStage.CONNECTING -> "正在建立 TCP 连接..."
            ConnectionStage.AUTHENTICATING -> "正在进行身份认证..."
            ConnectionStage.CONNECTED -> "连接成功"
            ConnectionStage.FAILED -> statusText
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val content = stream.bufferedReader().use { it.readText() }
                privateKeyText = content
                savedKeyFileName = uri.lastPathSegment ?: "已选择私钥文件"
            }
        } catch (e: Exception) {
            statusText = "读取私钥文件失败: ${e.message}"
        }
    }

    fun saveCurrentConfig() {
        scope.launch {
            repository.save(
                ConnectionConfig(
                    host = host,
                    port = port.toIntOrNull() ?: 22,
                    username = username,
                    authType = selectedAuth,
                    password = password,
                    privateKeyContent = privateKeyText,
                    savedKeyFileName = savedKeyFileName,
                    autoConnect = true
                )
            )
        }
    }

    fun doConnect() {
        scope.launch {
            isConnecting = true
            saveCurrentConfig()
            val result = when (selectedAuth) {
                AuthType.PASSWORD -> sshManager.connectWithPassword(
                    host = host,
                    port = port.toIntOrNull() ?: 22,
                    username = username,
                    password = password
                )
                AuthType.PPK_TEXT, AuthType.FILE_KEY -> sshManager.connectWithPrivateKey(
                    host = host,
                    port = port.toIntOrNull() ?: 22,
                    username = username,
                    privateKeyContent = privateKeyText
                )
            }
            result.onSuccess {
                statusText = "连接成功"
                onConnected()
            }.onFailure {
                statusText = "连接失败: ${it.message ?: "未知错误"}"
            }
            isConnecting = false
        }
    }

    fun doDiscover() {
        scope.launch {
            isDiscovering = true
            showDiscoveryDialog = true
            discoveredHosts = emptyList()
            discoveryProgress = 0 to 0
            val hosts = AutoDiscovery.findSshHosts(
                port = port.toIntOrNull() ?: 22,
                timeoutMs = 400,
                onProgress = { checked, total ->
                    discoveryProgress = checked to total
                }
            )
            discoveredHosts = hosts
            isDiscovering = false
        }
    }

    fun doAutoConnect() {
        scope.launch {
            isConnecting = true
            statusText = "正在扫描并连接 C3..."
            val keyContent = privateKeyText.ifBlank {
                loadDefaultPrivateKey().also { key ->
                    if (key.isNotBlank()) savedKeyFileName = "menmen.ppk"
                }
            }
            privateKeyText = keyContent
            selectedAuth = AuthType.PPK_TEXT

            val result = sshManager.connectWithAutoDiscovery(
                port = port.toIntOrNull() ?: 22,
                username = username,
                privateKeyContent = keyContent,
                timeoutMs = 400,
                onProgress = { checked, total ->
                    discoveryProgress = checked to total
                    statusText = "正在扫描... $checked / $total"
                }
            )
            result.onSuccess { connectedHost ->
                host = connectedHost
                statusText = "自动连接成功: $connectedHost"
                saveCurrentConfig()
                onConnected()
            }.onFailure {
                statusText = "自动连接失败: ${it.message ?: "未知错误"}"
            }
            isConnecting = false
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Left: SSH form
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .padding(28.dp)
                .verticalScroll(rememberScrollState())
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { doDiscover() },
                        enabled = !isDiscovering && !isConnecting,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isDiscovering) "扫描中" else "自动发现")
                    }
                    Button(
                        onClick = { doAutoConnect() },
                        enabled = !isDiscovering && !isConnecting,
                        shape = RoundedCornerShape(999.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Amber500)
                    ) {
                        Icon(Icons.Default.Bolt, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isConnecting) "连接中" else "自动连接")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Amber100,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "不知道如何填写 IP 和端口？点击右上角「自动发现」扫描局域网设备，或点击「自动连接」直接用 menmen.ppk 连回 C3。C3 默认 IP 常为 192.168.43.1（手机做热点时）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Amber500,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "支持密码、PPK 私钥文本和文件私钥三种登录方式。",
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
                AuthTab("密码登录", selectedAuth == AuthType.PASSWORD) { selectedAuth = AuthType.PASSWORD }
                AuthTab("PPK 私钥", selectedAuth == AuthType.PPK_TEXT) { selectedAuth = AuthType.PPK_TEXT }
                AuthTab("文件私钥", selectedAuth == AuthType.FILE_KEY) { selectedAuth = AuthType.FILE_KEY }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (selectedAuth) {
                AuthType.PASSWORD -> {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                AuthType.PPK_TEXT -> {
                    OutlinedTextField(
                        value = privateKeyText,
                        onValueChange = { privateKeyText = it },
                        label = { Text("在此粘贴私钥内容（PEM / OpenSSH / PPK）") },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        maxLines = 8
                    )
                }
                AuthType.FILE_KEY -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Slate100,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clickable {
                                filePicker.launch(arrayOf("*/*"))
                            }
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FileUpload, null, tint = Slate600)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = savedKeyFileName.ifBlank { "点击选择私钥文件" },
                                    color = Slate600,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { doConnect() },
                enabled = !isConnecting && !isDiscovering,
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(if (isConnecting) stageToText(stage) else "连接设备")
            }
        }

        // Right: status, stages, saved configs
        Column(
            modifier = Modifier
                .weight(0.7f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            StatusCard(
                title = "连接状态",
                content = statusText
            )

            StageCard(stage = stage)

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Panel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        text = "已保存配置",
                        style = MaterialTheme.typography.titleLarge,
                        color = Slate900
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "IP: $host\n端口: $port\n用户: $username\n认证: ${authTypeLabel(selectedAuth)}\n私钥文件: ${savedKeyFileName.ifBlank { "无" }}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Slate600
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { saveCurrentConfig() },
                            enabled = !isConnecting,
                            shape = RoundedCornerShape(999.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                        ) {
                            Text("保存配置")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repository.clear()
                                    host = "192.168.43.1"
                                    port = "22"
                                    username = SshManager.DEFAULT_USER
                                    password = ""
                                    privateKeyText = ""
                                    savedKeyFileName = ""
                                    selectedAuth = AuthType.PASSWORD
                                }
                            },
                            enabled = !isConnecting,
                            shape = RoundedCornerShape(999.dp)
                        ) {
                            Text("清除")
                        }
                    }
                }
            }
        }
    }

    if (showDiscoveryDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDiscovering) showDiscoveryDialog = false },
            title = { Text("局域网设备扫描") },
            text = {
                Column {
                    if (isDiscovering) {
                        LinearProgressIndicator(
                            progress = if (discoveryProgress.second > 0) {
                                discoveryProgress.first.toFloat() / discoveryProgress.second.toFloat()
                            } else 0f,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("已扫描 ${discoveryProgress.first} / ${discoveryProgress.second}")
                    } else {
                        if (discoveredHosts.isEmpty()) {
                            Text("未找到开启 22 端口的设备。请确认：\n1. 手机与 C3 在同一网络\n2. C3 的 SSH 服务已开启\n3. 端口填写正确")
                        } else {
                            Text("发现以下设备：")
                            Spacer(modifier = Modifier.height(8.dp))
                            discoveredHosts.forEach { discovered ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            host = discovered.host
                                            showDiscoveryDialog = false
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Router, null, tint = Teal500)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("${discovered.host}:${discovered.port}")
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showDiscoveryDialog = false },
                    enabled = !isDiscovering
                ) {
                    Text("关闭")
                }
            }
        )
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

@Composable
private fun StageCard(stage: ConnectionStage) {
    val stages = listOf(
        ConnectionStage.RESOLVING to "解析地址",
        ConnectionStage.CONNECTING to "TCP 握手",
        ConnectionStage.AUTHENTICATING to "身份认证",
        ConnectionStage.CONNECTED to "连接成功"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Panel)
            .padding(24.dp)
    ) {
        Text(
            text = "握手过程",
            style = MaterialTheme.typography.titleLarge,
            color = Slate900
        )
        Spacer(modifier = Modifier.height(16.dp))
        stages.forEachIndexed { index, (s, label) ->
            val isDone = stage.ordinal > s.ordinal
            val isActive = stage == s
            val color = when {
                stage == ConnectionStage.FAILED -> if (isDone) Green500 else Slate400
                isDone -> Green500
                isActive -> Teal500
                else -> Slate400
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = color
                )
                if (isDone) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.Default.Check, null, tint = Green500, modifier = Modifier.size(16.dp))
                }
            }
            if (index < stages.size - 1) {
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp, top = 4.dp, bottom = 4.dp)
                        .width(2.dp)
                        .height(16.dp)
                        .background(if (isDone) Green500 else Slate200)
                )
            }
        }
        if (stage == ConnectionStage.FAILED) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "连接失败，请检查 IP、端口、用户名或认证凭证",
                color = Red500,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun stageToText(stage: ConnectionStage): String = when (stage) {
    ConnectionStage.IDLE -> "连接中..."
    ConnectionStage.RESOLVING -> "解析中..."
    ConnectionStage.CONNECTING -> "握手中..."
    ConnectionStage.AUTHENTICATING -> "认证中..."
    ConnectionStage.CONNECTED -> "已连接"
    ConnectionStage.FAILED -> "重试"
}

private fun authTypeLabel(type: AuthType): String = when (type) {
    AuthType.PASSWORD -> "密码登录"
    AuthType.PPK_TEXT -> "PPK 私钥文本"
    AuthType.FILE_KEY -> "文件私钥"
}
