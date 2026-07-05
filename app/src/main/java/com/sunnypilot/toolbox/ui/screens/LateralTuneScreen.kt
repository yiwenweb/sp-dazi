package com.sunnypilot.toolbox.ui.screens

import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.data.repository.LateralTuneRepository
import com.sunnypilot.toolbox.model.LateralTuneResult
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LateralTuneScreen(
    sshManager: SshManager,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { LateralTuneRepository(sshManager) }

    var maxSegments by remember { mutableStateOf("5") }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<LateralTuneResult?>(null) }

    fun analyze() {
        val segments = maxSegments.toIntOrNull() ?: 5
        scope.launch {
            isLoading = true
            error = null
            result = null
            repository.analyze(segments).fold(
                onSuccess = { result = it },
                onFailure = { e ->
                    error = e.message
                    Toast.makeText(context, "分析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
            isLoading = false
        }
    }

    fun downloadReport() {
        val remotePath = result?.path ?: return
        scope.launch {
            val downloadDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "SunnyPilot_Tune")
            downloadDir.mkdirs()
            val fileName = remotePath.substringAfterLast("/")
            val localFile = File(downloadDir, fileName)
            repository.downloadReport(remotePath, localFile).fold(
                onSuccess = {
                    Toast.makeText(context, "已下载到: ${localFile.absolutePath}", Toast.LENGTH_LONG).show()
                },
                onFailure = { e ->
                    Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "智能计算",
                style = MaterialTheme.typography.headlineMedium,
                color = Slate900,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = { analyze() }, enabled = !isLoading) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "刷新",
                    tint = Teal500
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "分析 C3 历史驾驶日志，生成横向控制参数建议报告。当前版本不会直接修改 C3 参数。",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600
        )
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = Slate50),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "分析设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate900
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = maxSegments,
                    onValueChange = { maxSegments = it.filter { ch -> ch.isDigit() } },
                    label = { Text("分析最近 segment 数量") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { analyze() },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isLoading) "分析中..." else "开始计算")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Teal500)
            }
        } else if (error != null) {
            ErrorTuneState(error = error!!, onRetry = { analyze() })
        } else if (result != null) {
            TuneResultCard(
                result = result!!,
                onDownload = { downloadReport() }
            )
        }
    }
}

@Composable
private fun TuneResultCard(
    result: LateralTuneResult,
    onDownload: () -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = Teal50),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "分析完成",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Teal700
                )
                Text(
                    text = "共分析 ${result.segments_analyzed} 个 segment",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "C3 路径: ${result.path}",
                style = MaterialTheme.typography.bodySmall,
                color = Slate500
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onDownload,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Teal500)
            ) {
                Icon(Icons.Default.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("下载报告到手机")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "报告内容预览",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Slate900
            )
            Spacer(modifier = Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = result.report,
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate700,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ErrorTuneState(error: String, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp)
    ) {
        Text("分析失败", color = Slate600, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text(error, color = Red500, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}
