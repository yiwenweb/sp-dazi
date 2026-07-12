package com.sunnypilot.toolbox.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.DebugRepository
import com.sunnypilot.toolbox.model.DebugEvent
import com.sunnypilot.toolbox.model.DebugStatus
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DEBUG 抓包功能页面。
 *
 * 架构说明: C3 上常驻守护脚本 byd_debug_watchdog.py 做黑匣子(环形缓冲+多故障检测+自动存证),
 * 本页面只负责: 部署脚本 / 开关监测 / 查状态 / 列事件 / 下载分析。
 * 开关语义 = "让 C3 常驻监测", 开启后手机可断开, 开车时照抓, 事后连上取证。
 */
@Composable
fun DebugScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DebugRepository(context, sshManager) }

    var status by remember { mutableStateOf(DebugStatus()) }
    var events by remember { mutableStateOf<List<DebugEvent>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var scriptDeployed by remember { mutableStateOf<Boolean?>(null) }
    var selectedReport by remember { mutableStateOf<String?>(null) }
    var showReportTitle by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            repository.readStatus().onSuccess { status = it }
            repository.listEvents().onSuccess { events = it }
            repository.isScriptDeployed().onSuccess { scriptDeployed = it }
        }
    }

    // 部署脚本(从 assets 读取内容写到 C3)
    fun deploy() {
        scope.launch {
            busy = true
            try {
                val content = context.assets.open("byd_debug_watchdog.py")
                    .bufferedReader().use { it.readText() }
                repository.deployScript(content).fold(
                    onSuccess = {
                        scriptDeployed = true
                        Toast.makeText(context, "守护脚本已部署到 C3", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(context, "部署失败: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(context, "读取脚本失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            busy = false
        }
    }

    fun toggle(on: Boolean) {
        scope.launch {
            busy = true
            if (on) {
                // 确保脚本已部署
                val deployed = repository.isScriptDeployed().getOrDefault(false)
                if (!deployed) {
                    val content = try {
                        context.assets.open("byd_debug_watchdog.py").bufferedReader().use { it.readText() }
                    } catch (e: Exception) { "" }
                    if (content.isNotBlank()) repository.deployScript(content)
                }
                repository.startWatchdog().fold(
                    onSuccess = { Toast.makeText(context, "监测已开启(C3常驻)", Toast.LENGTH_SHORT).show() },
                    onFailure = { e -> Toast.makeText(context, "开启失败: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            } else {
                repository.stopWatchdog().fold(
                    onSuccess = { Toast.makeText(context, "监测已关闭", Toast.LENGTH_SHORT).show() },
                    onFailure = { e -> Toast.makeText(context, "关闭失败: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            }
            delay(500)
            repository.readStatus().onSuccess { status = it }
            busy = false
        }
    }

    // 首次加载 + 轮询状态(每3s)
    LaunchedEffect(Unit) {
        if (sshManager.isConnected()) refresh()
    }
    LaunchedEffect(sshManager.isConnected()) {
        while (true) {
            if (sshManager.isConnected()) {
                repository.readStatus().onSuccess { status = it }
            }
            delay(3000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // ── 标题 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text("故障抓包 (DEBUG)", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Slate900)
                Text(
                    "C3 常驻黑匣子: 自动检测锁死/失力/ACC异常, 存证前后各1分钟全量CAN",
                    fontSize = 13.sp, color = Slate500
                )
            }
            OutlinedButton(onClick = { refresh() }, enabled = !busy) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }

        if (!sshManager.isConnected()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Amber50)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = Amber500)
                    Spacer(Modifier.width(12.dp))
                    Text("请先连接 C3 设备", color = Slate700, fontSize = 14.sp)
                }
            }
            return@Column
        }

        // ── 开关卡片 ──
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (status.active) Teal50 else Panel
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (status.active) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        null,
                        tint = if (status.active) Teal500 else Slate400,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (status.active) "监测运行中" else "监测已关闭",
                            fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Slate900
                        )
                        Text(
                            when {
                                !status.running && status.active -> "守护进程未运行(异常), 请重新开启"
                                status.active -> status.note.ifBlank { "C3 常驻监测中" }
                                else -> "点击开关让 C3 开始常驻监测"
                            },
                            fontSize = 12.sp, color = Slate500
                        )
                    }
                    Switch(
                        checked = status.active,
                        onCheckedChange = { if (!busy) toggle(it) },
                        enabled = !busy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Teal500
                        )
                    )
                }

                if (status.active) {
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        StatChip("EPS", status.eps, if (status.eps == "LOCKED") Red500 else Green500)
                        StatChip("缓冲", "${status.buffer}", Slate600)
                        StatChip("已捕获", "${status.triggers}", if (status.triggers > 0) Amber500 else Slate600)
                        if (status.dumping) StatChip("状态", "录制中", Amber500)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("心跳: ${status.time}", fontSize = 11.sp, color = Slate400)
                }
            }
        }

        // ── 脚本部署状态 ──
        if (scriptDeployed == false) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Amber50)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("守护脚本尚未部署到 C3", fontWeight = FontWeight.SemiBold, color = Slate700, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("首次使用需部署脚本(或直接开启监测会自动部署)。", fontSize = 12.sp, color = Slate500)
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { deploy() },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                    ) {
                        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("部署守护脚本")
                    }
                }
            }
        }

        // ── 事件列表 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("已捕获故障事件 (${events.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate900)
            if (events.isNotEmpty()) {
                TextButton(onClick = { refresh() }) { Text("重新加载", fontSize = 12.sp, color = Teal500) }
            }
        }

        if (events.isEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate50)
            ) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircle, null, tint = Green500, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("暂无故障事件", fontSize = 14.sp, color = Slate600)
                    Text("开启监测后, 发生锁死/失力/ACC异常会自动记录在此", fontSize = 12.sp, color = Slate400)
                }
            }
        } else {
            events.forEach { ev ->
                EventCard(
                    event = ev,
                    busy = busy,
                    onViewReport = {
                        scope.launch {
                            busy = true
                            repository.readReport(ev.dirName).fold(
                                onSuccess = { showReportTitle = ev.faultName; selectedReport = it },
                                onFailure = { e -> Toast.makeText(context, "读取报告失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            )
                            busy = false
                        }
                    },
                    onDownload = {
                        scope.launch {
                            busy = true
                            repository.downloadDump(ev.dirName).fold(
                                onSuccess = { f -> Toast.makeText(context, "已下载到: ${f.absolutePath}", Toast.LENGTH_LONG).show() },
                                onFailure = { e -> Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show() }
                            )
                            busy = false
                        }
                    },
                    onDelete = {
                        scope.launch {
                            busy = true
                            repository.deleteEvent(ev.dirName).fold(
                                onSuccess = {
                                    Toast.makeText(context, "已删除", Toast.LENGTH_SHORT).show()
                                    repository.listEvents().onSuccess { events = it }
                                },
                                onFailure = { e -> Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                            )
                            busy = false
                        }
                    }
                )
            }
        }

        // ── 说明 ──
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate50)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("工作原理", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Slate700)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. 开启后守护脚本在 C3 常驻(nohup), 手机可断开, 开车时照抓\n" +
                            "2. 内存滚动缓冲最近90秒全量CAN, 命中故障后再录60秒 → 前后各约1分钟\n" +
                            "3. 一期覆盖: EPS锁死/转向被切断/ACC意外退出/进程消失/OP告警\n" +
                            "4. 自动限量清理(最多留20个事件), 防磁盘满\n" +
                            "5. 自动分析只给一级线索, 深挖需下载 dump.jsonl 交给AI/脚本",
                    fontSize = 12.sp, color = Slate500, lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    // ── 报告弹窗 ──
    if (selectedReport != null) {
        AlertDialog(
            onDismissRequest = { selectedReport = null },
            title = { Text(showReportTitle, fontWeight = FontWeight.Bold, color = Slate900) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(selectedReport ?: "", fontSize = 12.sp, color = Slate600, lineHeight = 18.sp)
                }
            },
            confirmButton = { TextButton(onClick = { selectedReport = null }) { Text("关闭", color = Teal500) } }
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, valueColor: Color) {
    Column {
        Text(label, fontSize = 11.sp, color = Slate400)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
private fun EventCard(
    event: DebugEvent,
    busy: Boolean,
    onViewReport: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    val faultColor = when {
        event.fault.startsWith("LAT") -> Red500
        event.fault.startsWith("LON") -> Amber500
        event.fault.startsWith("SYS") -> Purple500
        else -> Blue500
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(6.dp), color = faultColor) {
                    Text(
                        event.fault,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(event.faultName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Slate900, modifier = Modifier.weight(1f))
                if (event.hasDump) {
                    Icon(Icons.Default.Save, null, tint = Green500, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(event.triggerTimeStr, fontSize = 12.sp, color = Slate500)
            if (event.clue.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Slate50, modifier = Modifier.fillMaxWidth()) {
                    Text("线索: ${event.clue}", fontSize = 12.sp, color = Slate600, modifier = Modifier.padding(10.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("数据: ${event.records}条 / 跨度${event.spanSec.toInt()}秒 (前${event.bufferSec}s+后${event.postSec.toInt()}s)",
                fontSize = 11.sp, color = Slate400)

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onViewReport, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Description, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("报告", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onDownload, enabled = !busy && event.hasDump, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDelete, enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Red500)
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
