package com.sunnypilot.toolbox.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.SshShell
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TerminalScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var terminalText by remember { mutableStateOf("") }
    var inputText by remember { mutableStateOf("") }
    var realTimeInput by remember { mutableStateOf(false) }
    var isConnected by remember { mutableStateOf(false) }
    var shell by remember { mutableStateOf<SshShell?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun sendRaw(text: String) {
        shell?.outputStream?.write(text.toByteArray(Charsets.UTF_8))
        shell?.outputStream?.flush()
    }

    fun sendInput(text: String) {
        sendRaw(text)
        if (!realTimeInput) {
            inputText = ""
        }
    }

    fun onEnter() {
        if (realTimeInput) {
            sendRaw("\r")
        } else {
            sendInput("$inputText\r")
            inputText = ""
        }
    }

    fun onRealtimeChar(char: String) {
        if (realTimeInput) {
            sendRaw(char)
        } else {
            inputText += char
        }
    }

    LaunchedEffect(sshManager.isConnected()) {
        if (sshManager.isConnected() && shell == null) {
            sshManager.openShell().fold(
                onSuccess = { s ->
                    shell = s
                    isConnected = true
                    errorMessage = null
                    scope.launch(Dispatchers.IO) {
                        val buffer = ByteArray(1024)
                        try {
                            while (s.isConnected()) {
                                val count = s.inputStream.read(buffer)
                                if (count > 0) {
                                    val text = String(buffer, 0, count, Charsets.UTF_8)
                                    withContext(Dispatchers.Main) {
                                        terminalText += text
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            isConnected = false
                        }
                    }
                },
                onFailure = { e ->
                    errorMessage = e.message
                    isConnected = false
                }
            )
        }
    }

    // 自动滚动到底部
    LaunchedEffect(terminalText) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 终端窗口
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF0F172A))
                .padding(16.dp)
        ) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF5F57))
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFEBC2E))
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF28C840))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "openpilot_toolbox ~ bash",
                        color = Color(0xFF94A3B8),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isConnected) Green500 else Red500)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isConnected) "CONNECTED" else "DISCONNECTED",
                        color = if (isConnected) Green500 else Red500,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 终端内容
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = terminalText,
                    color = Color(0xFFE2E8F0),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 输入区域
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Panel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 输入框
                BasicTextField(
                    value = inputText,
                    onValueChange = { newValue ->
                        if (realTimeInput) {
                            val diff = newValue.removePrefix(inputText)
                            if (diff.length == 1) {
                                sendRaw(diff)
                            } else if (newValue.length < inputText.length) {
                                // 退格
                                sendRaw("\u007F")
                            }
                            inputText = newValue
                        } else {
                            inputText = newValue
                        }
                    },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = Slate900
                    ),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Slate100)
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            if (inputText.isEmpty()) {
                                Text(
                                    text = "在此输入命令...",
                                    color = Slate400,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp
                                    )
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 快捷按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TerminalButton(
                        text = "粘贴",
                        color = Blue500,
                        bgColor = Blue100,
                        onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (realTimeInput) {
                                sendRaw(clip)
                            } else {
                                inputText += clip
                            }
                        }
                    )
                    TerminalButton(
                        text = "Enter",
                        color = Teal500,
                        bgColor = Teal50,
                        onClick = { onEnter() }
                    )
                    TerminalButton(
                        text = "Tab",
                        color = Slate600,
                        bgColor = Slate200,
                        onClick = { sendRaw("\t") }
                    )
                    TerminalButton(
                        text = "Ctrl+C",
                        color = Red500,
                        bgColor = Red100,
                        onClick = { sendRaw("\u0003") }
                    )
                    TerminalButton(
                        text = "Esc",
                        color = Slate600,
                        bgColor = Slate200,
                        onClick = { sendRaw("\u001B") }
                    )
                    TerminalButton(
                        text = "↑",
                        color = Slate600,
                        bgColor = Slate200,
                        onClick = { sendRaw("\u001B[A") }
                    )
                    TerminalButton(
                        text = "↓",
                        color = Slate600,
                        bgColor = Slate200,
                        onClick = { sendRaw("\u001B[B") }
                    )
                    TerminalButton(
                        text = "清屏",
                        color = Amber500,
                        bgColor = Amber100,
                        onClick = { terminalText = "" }
                    )
                }

                // 实时输入开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "实时输入（逐字发送）",
                        color = Slate600,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = realTimeInput,
                        onCheckedChange = { realTimeInput = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Teal500,
                            checkedTrackColor = Teal100
                        )
                    )
                }
            }
        }

        errorMessage?.let { error ->
            Text(
                text = "连接失败: $error",
                color = Red500,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun TerminalButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    bgColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
