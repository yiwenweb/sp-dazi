package com.sunnypilot.toolbox

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sunnypilot.toolbox.data.BackupManager
import com.sunnypilot.toolbox.data.ConnectionConfigRepository
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.model.ConnectionConfig
import com.sunnypilot.toolbox.ui.components.NavItem
import com.sunnypilot.toolbox.ui.components.SideNavBar
import com.sunnypilot.toolbox.ui.components.TopBar
import com.sunnypilot.toolbox.ui.screens.ConnectionScreen
import com.sunnypilot.toolbox.ui.screens.DataCenterScreen
import com.sunnypilot.toolbox.ui.screens.DeviceDashboardScreen
import com.sunnypilot.toolbox.ui.screens.DeviceManagerScreen
import com.sunnypilot.toolbox.ui.screens.LateralParamsScreen
import com.sunnypilot.toolbox.ui.screens.LateralTuneScreen
import com.sunnypilot.toolbox.ui.screens.RecorderScreen
import com.sunnypilot.toolbox.ui.screens.SettingsScreen
import com.sunnypilot.toolbox.ui.screens.TerminalScreen
import com.sunnypilot.toolbox.ui.screens.VideoScreen
import com.sunnypilot.toolbox.ui.screens.FileScreen
import com.sunnypilot.toolbox.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val sshManager = SshManager()
    private val configRepository by lazy { ConnectionConfigRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFullScreen()
        setContent {
            SunnyPilotToolboxTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Background
                ) {
                    MainScreen(
                        sshManager = sshManager,
                        configRepository = configRepository
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullScreen()
    }

    private fun setFullScreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
        } catch (e: Exception) {
            // 某些定制系统不支持全屏 API，忽略错误即可
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sshManager.disconnect()
    }
}

@Composable
fun MainScreen(
    sshManager: SshManager,
    configRepository: ConnectionConfigRepository
) {
    var selectedNav by remember { mutableStateOf(NavItem.Connection) }
    var isConnected by remember { mutableStateOf(sshManager.isConnected()) }

    // DPI 密度调节
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("dpi_settings", Context.MODE_PRIVATE) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var densityScale by remember { mutableFloatStateOf(prefs.getFloat("density_scale", 1.0f)) }

    // 备份/恢复状态
    var isBackingUp by remember { mutableStateOf(false) }
    var backupProgress by remember { mutableStateOf("") }
    var backupResult by remember { mutableStateOf("") }
    var showBackupResult by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf("") }
    var showRestoreResult by remember { mutableStateOf(false) }

    // 读取当前连接配置（用于备份）
    val connectionConfig by configRepository.configFlow.collectAsState(
        initial = ConnectionConfig()
    )

    val restoreFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isBackingUp = true
                backupProgress = "正在恢复…"
                val dbPath = context.getDatabasePath("toolbox_database").absolutePath
                BackupManager.restoreFromBackup(context, uri, dbPath, configRepository) { stage ->
                    backupProgress = stage
                }.fold(
                    onSuccess = { msg -> restoreResult = msg },
                    onFailure = { e -> restoreResult = "恢复失败：${e.message}" }
                )
                isBackingUp = false
                showRestoreResult = true
            }
        }
    }

    fun performBackup() {
        scope.launch {
            isBackingUp = true
            backupProgress = "准备中…"
            val dbPath = context.getDatabasePath("toolbox_database").absolutePath
            BackupManager.createBackup(context, dbPath, connectionConfig) { stage ->
                backupProgress = stage
            }.fold(
                onSuccess = { backupFile ->
                    backupResult = "备份成功！\n文件：${backupFile.name}\n大小：${"%,d".format(backupFile.length())} 字节"
                    BackupManager.shareBackup(context, backupFile)
                },
                onFailure = { e ->
                    backupResult = "备份失败：${e.message}"
                }
            )
            isBackingUp = false
            showBackupResult = true
        }
    }

    // 基于当前系统密度乘以缩放系数
    val baseDensity = LocalDensity.current
    val adjustedDensity = remember(densityScale) {
        Density(
            density = baseDensity.density * densityScale,
            fontScale = densityScale
        )
    }

    val onConnected: () -> Unit = {
        isConnected = true
        selectedNav = NavItem.Hardware
    }

    val onDisconnected: () -> Unit = {
        sshManager.disconnect()
        isConnected = false
        selectedNav = NavItem.Connection
    }

    // 启动时若已保存自动连接配置，则直接连回 C3
    LaunchedEffect(Unit) {
        val config = configRepository.configFlow.first()
        if (config.autoConnect && !sshManager.isConnected()) {
            val keyContent = config.privateKeyContent
            if (keyContent.isNotBlank() && config.host.isNotBlank()) {
                sshManager.tryConnect(
                    host = config.host,
                    port = config.port,
                    username = config.username,
                    privateKeyContent = keyContent
                ).onSuccess {
                    onConnected()
                }
            }
        }
    }


    CompositionLocalProvider(LocalDensity provides adjustedDensity) {
        Row(modifier = Modifier.fillMaxSize()) {
            SideNavBar(
                selectedItem = selectedNav,
                onItemSelected = {
                    selectedNav = it
                },
                modifier = Modifier.fillMaxHeight()
            )

            Column(modifier = Modifier.weight(1f)) {
                TopBar(
                    moduleName = selectedNav.title,
                    isConnected = isConnected,
                    onRefresh = { isConnected = sshManager.isConnected() },
                    onSettings = { showSettingsDialog = true },
                    onDisconnect = onDisconnected
                )

                Box(modifier = Modifier.weight(1f)) {
                    if (!isConnected) {
                        // 未连接时固定显示登录页
                        ConnectionScreen(
                            sshManager = sshManager,
                            repository = configRepository,
                            onConnected = onConnected
                        )
                    } else {
                        when (selectedNav) {
                            NavItem.Connection -> ConnectionScreen(
                                sshManager = sshManager,
                                repository = configRepository,
                                onConnected = { isConnected = true }
                            )
                            NavItem.Hardware -> DeviceDashboardScreen(
                                sshManager = sshManager,
                                onDisconnected = onDisconnected
                            )
                            NavItem.Device -> DeviceManagerScreen(
                                sshManager = sshManager
                            )
                            NavItem.Terminal -> TerminalScreen(
                                sshManager = sshManager
                            )
                            NavItem.Data -> DataCenterScreen(
                                sshManager = sshManager
                            )
                            NavItem.Recorder -> RecorderScreen(
                                sshManager = sshManager
                            )
                            NavItem.Settings -> SettingsScreen(
                                sshManager = sshManager
                            )
                            NavItem.Calc -> LateralTuneScreen(
                                sshManager = sshManager
                            )
                            NavItem.Tune -> LateralParamsScreen(
                                sshManager = sshManager
                            )
                            NavItem.Video -> VideoScreen(
                                sshManager = sshManager
                            )
                            NavItem.Files -> FileScreen(
                                sshManager = sshManager
                            )
                            else -> DeviceManagerScreen(
                                sshManager = sshManager
                            )
                        }
                    }
                }
            }
        }

        // 设置弹窗（DPI + 一键备份）
        if (showSettingsDialog) {
            SettingsPanel(
                context = context,
                currentScale = densityScale,
                isBackingUp = isBackingUp,
                backupProgress = backupProgress,
                backupResult = backupResult,
                showBackupResult = showBackupResult,
                restoreResult = restoreResult,
                showRestoreResult = showRestoreResult,
                onDismiss = { showSettingsDialog = false },
                onScaleChange = { newScale ->
                    densityScale = newScale
                    prefs.edit().putFloat("density_scale", newScale).apply()
                },
                onBackup = { performBackup() },
                onRestore = { restoreFileLauncher.launch(arrayOf("application/zip", "*/*")) }
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    context: Context,
    currentScale: Float,
    isBackingUp: Boolean,
    backupProgress: String,
    backupResult: String,
    showBackupResult: Boolean,
    restoreResult: String,
    showRestoreResult: Boolean,
    onDismiss: () -> Unit,
    onScaleChange: (Float) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentScale) }
    var showRestoreConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("应用设置", fontWeight = FontWeight.Bold, color = Slate900) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── DPI 调节 ──
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("DPI 密度调节", fontWeight = FontWeight.SemiBold, color = Slate700, fontSize = 15.sp)
                    Text(
                        "缩放：${String.format("%.0f", sliderValue * 100)}%",
                        fontSize = 14.sp, color = Slate600
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("小", fontSize = 12.sp, color = Slate500)
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            valueRange = 0.5f..1.5f,
                            steps = 19,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Teal500, activeTrackColor = Teal500)
                        )
                        Text("大", fontSize = 12.sp, color = Slate500)
                    }
                    Button(
                        onClick = { onScaleChange(sliderValue) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Teal500)
                    ) { Text("应用 DPI") }
                }

                HorizontalDivider(color = Slate200)

                // ── 一键备份 ──
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Backup, null, tint = Blue500, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("数据备份与恢复", fontWeight = FontWeight.SemiBold, color = Slate700, fontSize = 15.sp)
                    }
                    Text(
                        "备份内容：数据库（驾驶统计、快捷命令）、连接配置、DPI 设置",
                        fontSize = 12.sp, color = Slate500, lineHeight = 18.sp
                    )

                    if (isBackingUp) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator(color = Teal500, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(backupProgress, fontSize = 12.sp, color = Slate600)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onBackup,
                            enabled = !isBackingUp,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Blue500)
                        ) {
                            Icon(Icons.Default.Backup, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("一键备份")
                        }
                        Button(
                            onClick = { showRestoreConfirm = true },
                            enabled = !isBackingUp,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Amber500)
                        ) {
                            Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("恢复数据")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = Teal500) }
        }
    )

    // ── 备份完成对话框 ──
    if (showBackupResult) {
        AlertDialog(
            onDismissRequest = { showBackupResult = false },
            title = { Text("备份结果", color = Slate900) },
            text = { Text(backupResult, fontSize = 14.sp, color = Slate600) },
            confirmButton = { TextButton(onClick = { showBackupResult = false }) { Text("确定", color = Teal500) } }
        )
    }

    // ── 恢复确认对话框 ──
    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("恢复数据", fontWeight = FontWeight.Bold, color = Slate900) },
            text = {
                Text(
                    "将从备份 ZIP 文件恢复所有数据（数据库、连接配置、DPI设置）。\n\n⚠ 当前数据将被覆盖，建议先备份。\n恢复后请重启应用。",
                    fontSize = 14.sp, color = Slate600, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestoreConfirm = false
                        onRestore()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Amber500)
                ) { Text("选择备份文件") }
            },
            dismissButton = { TextButton(onClick = { showRestoreConfirm = false }) { Text("取消") } }
        )
    }

    // ── 恢复结果对话框 ──
    if (showRestoreResult) {
        AlertDialog(
            onDismissRequest = { showRestoreResult = false },
            title = { Text("恢复结果", color = Slate900) },
            text = { Text(restoreResult, fontSize = 14.sp, color = Slate600) },
            confirmButton = { TextButton(onClick = { showRestoreResult = false }) { Text("确定", color = Teal500) } }
        )
    }
}
