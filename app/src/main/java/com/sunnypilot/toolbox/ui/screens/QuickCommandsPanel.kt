package com.sunnypilot.toolbox.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sunnypilot.toolbox.model.QuickCommand
import com.sunnypilot.toolbox.ui.theme.*

@Composable
fun QuickCommandsPanel(
    commands: List<QuickCommand>,
    serverUrl: String?,
    serverRunning: Boolean,
    onRestartServer: () -> Unit,
    onExecute: (QuickCommand) -> Unit,
    onSave: (QuickCommand) -> Unit,
    onDelete: (QuickCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<QuickCommand?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showQrDialog by remember { mutableStateOf(false) }

    LaunchedEffect(serverUrl) {
        qrBitmap = serverUrl?.let { url ->
            withContext(Dispatchers.Default) {
                com.sunnypilot.toolbox.ui.util.QrCodeUtil.generateQrCode(url, 360)
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 220.dp, max = 260.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "快捷命令",
                    color = Slate900,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 二维码按钮
                    FilledIconButton(
                        onClick = { showQrDialog = true },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Blue500),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.QrCode2, contentDescription = "二维码", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    // 新增按钮
                    FilledIconButton(
                        onClick = {
                            editing = null
                            showDialog = true
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = Teal500),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "新增", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }

            // Command list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 固定命令：抓取CAN信号
                FixedCanCaptureCard(onExecute = onExecute)
                
                if (commands.isEmpty()) {
                    EmptyQuickCommandHint()
                } else {
                    commands.forEach { cmd ->
                        QuickCommandCard(
                            command = cmd,
                            onExecute = { onExecute(cmd) },
                            onEdit = {
                                editing = cmd
                                showDialog = true
                            },
                            onDelete = { onDelete(cmd) }
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        QuickCommandDialog(
            command = editing,
            onDismiss = { showDialog = false },
            onConfirm = { cmd ->
                onSave(cmd)
                showDialog = false
            }
        )
    }
    
    if (showQrDialog) {
        QrCodeDialog(
            qrBitmap = qrBitmap,
            serverUrl = serverUrl,
            serverRunning = serverRunning,
            onRestartServer = onRestartServer,
            onDismiss = { showQrDialog = false }
        )
    }
}

@Composable
private fun QuickCommandCard(
    command: QuickCommand,
    onExecute: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Slate50,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = command.title,
                        color = Slate900,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (command.description.isNotBlank()) {
                        Text(
                            text = command.description,
                            color = Slate500,
                            fontSize = 12.sp,
                            maxLines = 2,
                            lineHeight = 16.sp
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onExecute, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "执行",
                            tint = Green500,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "编辑",
                            tint = Blue500,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "删除",
                            tint = Red500,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FixedCanCaptureCard(
    onExecute: (QuickCommand) -> Unit
) {
    val timestamp = remember { java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date()) }
    val canCommand = remember {
        QuickCommand(
            id = -1L,
            title = "抓取CAN信号",
            command = "mkdir -p /data/appdata && cd /data/openpilot && timeout 60 python -c \"from cereal import messaging; import time; sock = messaging.sub_sock('can'); f = open('/data/appdata/fullcan_$timestamp.log', 'w'); [f.write(str(messaging.recv_one(sock)) + '\\\\n') for _ in range(10000)]; f.close()\" && echo '✓ CAN数据已保存到 /data/appdata/fullcan_$timestamp.log'",
            description = "抓取60秒CAN数据用于故障分析",
            sortOrder = -1
        )
    }
    
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Amber50,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = Amber600,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = canCommand.title,
                            color = Slate900,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = canCommand.description,
                            color = Slate500,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
                IconButton(
                    onClick = { onExecute(canCommand) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "执行",
                        tint = Green500,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCodeDialog(
    qrBitmap: Bitmap?,
    serverUrl: String?,
    serverRunning: Boolean,
    onRestartServer: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel,
            modifier = Modifier.widthIn(max = 360.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "扫码编辑命令",
                    color = Slate900,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (serverRunning) Green500 else Red500)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (serverRunning) "服务运行中" else "服务未启动",
                        color = if (serverRunning) Green500 else Red500,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    TextButton(
                        onClick = onRestartServer,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("重启服务", fontSize = 13.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "管理页面二维码",
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: run {
                        Text(
                            text = "未联网",
                            color = Slate400,
                            fontSize = 14.sp
                        )
                    }
                }
                
                Text(
                    text = serverUrl ?: "等待网络...",
                    color = Slate600,
                    fontSize = 13.sp
                )
                
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun EmptyQuickCommandHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Slate50)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Code,
                contentDescription = null,
                tint = Slate400,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "暂无快捷命令",
                color = Slate500,
                fontSize = 14.sp
            )
            Text(
                text = "点击右上角 + 新增",
                color = Slate400,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun QuickCommandDialog(
    command: QuickCommand?,
    onDismiss: () -> Unit,
    onConfirm: (QuickCommand) -> Unit
) {
    var title by remember { mutableStateOf(command?.title ?: "") }
    var cmd by remember { mutableStateOf(command?.command ?: "") }
    var description by remember { mutableStateOf(command?.description ?: "") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Panel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (command == null) "新增快捷命令" else "编辑快捷命令",
                    color = Slate900,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("命令名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    label = { Text("命令内容") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("作用说明") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = Slate600)
                    }
                    Button(
                        onClick = {
                            if (title.isBlank() || cmd.isBlank()) return@Button
                            onConfirm(
                                QuickCommand(
                                    id = command?.id ?: 0L,
                                    title = title.trim(),
                                    command = cmd,
                                    description = description.trim(),
                                    sortOrder = command?.sortOrder ?: 0
                                )
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}
