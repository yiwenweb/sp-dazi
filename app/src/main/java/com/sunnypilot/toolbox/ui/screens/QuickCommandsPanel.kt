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
            .widthIn(min = 240.dp, max = 280.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                FilledIconButton(
                    onClick = {
                        editing = null
                        showDialog = true
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Teal500)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新增", tint = Color.White)
                }
            }

            // Command list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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

            Divider(color = Slate200, thickness = 1.dp)

            // QR code
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "扫码编辑命令",
                    color = Slate600,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (serverRunning) Green500 else Red500)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (serverRunning) "服务运行中" else "服务未启动",
                        color = if (serverRunning) Green500 else Red500,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = onRestartServer,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("重启", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(6.dp),
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
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = serverUrl ?: "等待网络...",
                    color = Slate500,
                    fontSize = 11.sp,
                    maxLines = 1
                )
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
            Text(
                text = command.command,
                color = Slate600,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Slate100)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
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
