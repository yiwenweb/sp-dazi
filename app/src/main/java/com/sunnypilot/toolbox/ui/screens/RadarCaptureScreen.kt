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
import com.sunnypilot.toolbox.data.repository.RadarCaptureRepository
import com.sunnypilot.toolbox.model.RadarCaptureStatus
import com.sunnypilot.toolbox.model.RadarMark
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 雷达距离标定采集页 (方案1: 卷尺对照法)。
 *
 * 目的: 破解前向毫米波雷达的距离编码。做法是让车静止, 前方摆一个已知距离
 * (卷尺量)的反射目标, 在每个距离按"打点", 把雷达帧和精确距离绑定, 生成
 * 标定表, 离线即可解出距离字段(哪怕非线性)。
 */
@Composable
fun RadarCaptureScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { RadarCaptureRepository(context, sshManager) }

    var status by remember { mutableStateOf(RadarCaptureStatus()) }
    var marks by remember { mutableStateOf<List<RadarMark>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var distInput by remember { mutableStateOf("") }
    var showGuide by remember { mutableStateOf(true) }

    fun refresh() {
        scope.launch {
            repo.readStatus().onSuccess { status = it }
            repo.listMarks().onSuccess { marks = it }
        }
    }

    fun toggle(on: Boolean) {
        scope.launch {
            busy = true
            if (on) {
                repo.startCapture().fold(
                    onSuccess = { Toast.makeText(context, "采集已开始(C3常驻)", Toast.LENGTH_SHORT).show() },
                    onFailure = { e -> Toast.makeText(context, "开启失败: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            } else {
                repo.stopCapture().fold(
                    onSuccess = { Toast.makeText(context, "采集已停止", Toast.LENGTH_SHORT).show() },
                    onFailure = { e -> Toast.makeText(context, "停止失败: ${e.message}", Toast.LENGTH_LONG).show() }
                )
            }
            delay(500)
            repo.readStatus().onSuccess { status = it }
            busy = false
        }
    }

    fun doMark() {
        val d = distInput.toFloatOrNull()
        if (d == null || d <= 0) {
            Toast.makeText(context, "请输入有效的距离(米)", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            busy = true
            repo.mark(d).fold(
                onSuccess = {
                    Toast.makeText(context, "已打点 ${d}m", Toast.LENGTH_SHORT).show()
                    delay(600)
                    repo.listMarks().onSuccess { marks = it }
                },
                onFailure = { e -> Toast.makeText(context, "打点失败: ${e.message}", Toast.LENGTH_LONG).show() }
            )
            busy = false
        }
    }

    LaunchedEffect(Unit) { if (sshManager.isConnected()) refresh() }
    LaunchedEffect(sshManager.isConnected()) {
        while (true) {
            if (sshManager.isConnected()) repo.readStatus().onSuccess { status = it }
            delay(2000)
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
                Text("雷达距离标定", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Slate900)
                Text("卷尺对照法: 用已知距离破解雷达距离编码", fontSize = 13.sp, color = Slate500)
            }
            OutlinedButton(onClick = { refresh() }, enabled = !busy) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }

        if (!sshManager.isConnected()) {
            WarnCard("请先连接 C3 设备")
            return@Column
        }

        // ── 操作指引(可折叠) ──
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Teal50)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.MenuBook, null, tint = Teal700, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("操作步骤", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Slate900,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showGuide = !showGuide }) {
                        Text(if (showGuide) "收起" else "展开", color = Teal700, fontSize = 12.sp)
                    }
                }
                if (showGuide) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "准备:\n" +
                        "• 找空旷场地, 车熄火挂P, C3 保持运行(在录制)\n" +
                        "• 前方正中放一个反射目标: 一辆车 / 大金属板 / 角反射器(效果最好)\n" +
                        "• 备一把卷尺或激光测距仪\n\n" +
                        "采集:\n" +
                        "1. 点下方「开始采集」, 观察「实时目标」是否变绿(有目标)\n" +
                        "   - 若一直「未见目标」, 说明雷达没锁到, 让目标缓慢移动一下再试\n" +
                        "2. 把目标摆到第1个距离(如 5m), 用卷尺量准, 静止\n" +
                        "3. 在下方输入 5, 点「打点」, 等提示成功\n" +
                        "4. 目标移到下一个距离(10/15/20/30/40m...), 重复打点\n" +
                        "   - 距离跨度越大越好(近到远都要), 建议 6~10 个点\n" +
                        "5. 全部打完, 点「停止采集」, 再点「下载标定数据」\n" +
                        "6. 把下载的文件发回, 即可离线解出距离字段\n\n" +
                        "提示: 每次打点会抓取最近3秒的雷达帧和这个距离绑定。",
                        fontSize = 12.sp, color = Slate600, lineHeight = 18.sp
                    )
                }
            }
        }

        // ── 采集开关 + 实时目标状态 ──
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = if (status.active) Teal50 else Panel),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Sensors,
                        null,
                        tint = if (status.active) Teal500 else Slate400,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (status.active) "采集运行中" else "采集未开始",
                            fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Slate900
                        )
                        Text(status.note.ifBlank { "点击开关开始采集雷达帧" },
                            fontSize = 12.sp, color = Slate500)
                    }
                    Switch(
                        checked = status.active,
                        onCheckedChange = { if (!busy) toggle(it) },
                        enabled = !busy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White, checkedTrackColor = Teal500
                        )
                    )
                }

                if (status.active) {
                    Spacer(Modifier.height(14.dp))
                    // 实时目标指示(核心: 让用户知道雷达锁没锁到目标)
                    val hasTarget = status.targetFrames > 3
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (hasTarget) Green50 else Amber50,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (hasTarget) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    null,
                                    tint = if (hasTarget) Green600 else Amber500,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (hasTarget) "雷达已锁到目标 ✓ 可以打点" else "未见目标 - 让目标动一下",
                                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                    color = if (hasTarget) Green700 else Amber500
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("最近1秒: 雷达帧 ${status.recentFrames} / 有目标帧 ${status.targetFrames}",
                                fontSize = 12.sp, color = Slate600)
                            if (status.targetAddrs.isNotEmpty()) {
                                Text("有目标地址: ${status.targetAddrs.joinToString(" ")}",
                                    fontSize = 11.sp, color = Slate500)
                            }
                            Text("心跳: ${status.time}", fontSize = 10.sp, color = Slate400)
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    // 打点输入
                    Text("当前目标距离(卷尺量, 米):", fontSize = 13.sp, color = Slate700, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = distInput,
                            onValueChange = { distInput = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("如 20.0") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Teal500, cursorColor = Teal500
                            )
                        )
                        Button(
                            onClick = { doMark() },
                            enabled = !busy && distInput.isNotBlank(),
                            modifier = Modifier.height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                        ) {
                            Icon(Icons.Default.PushPin, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("打点")
                        }
                    }
                }
            }
        }

        // ── 已打点列表 ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("已标定点 (${marks.size})", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Slate900)
            if (marks.isNotEmpty()) {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        repo.clearMarks().onSuccess {
                            marks = emptyList()
                            Toast.makeText(context, "已清空标定点", Toast.LENGTH_SHORT).show()
                        }
                        busy = false
                    }
                }) { Text("清空", color = Red500, fontSize = 12.sp) }
            }
        }

        if (marks.isEmpty()) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Slate50)
            ) {
                Text("还没有标定点。开始采集后, 在每个已知距离点「打点」。",
                    fontSize = 13.sp, color = Slate500, modifier = Modifier.padding(20.dp))
            }
        } else {
            marks.forEach { m ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = Teal500) {
                            Text("#${m.index}", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("${m.distanceM} m", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            color = Slate900, modifier = Modifier.weight(1f))
                        Text("${m.frames} 帧", fontSize = 12.sp, color = Slate500)
                    }
                }
            }

            // ── 下载 ──
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val local = repo.localDownloadFile()
                        repo.downloadAllMarks(local).fold(
                            onSuccess = { f -> Toast.makeText(context, "已下载: ${f.absolutePath}", Toast.LENGTH_LONG).show() },
                            onFailure = { e -> Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show() }
                        )
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Teal500)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("下载标定数据 (发回分析)")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun WarnCard(text: String) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Amber50)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = Amber500)
            Spacer(Modifier.width(12.dp))
            Text(text, color = Slate700, fontSize = 14.sp)
        }
    }
}
