package com.sunnypilot.toolbox

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.sunnypilot.toolbox.data.ConnectionConfigRepository
import com.sunnypilot.toolbox.data.SshManager
import com.sunnypilot.toolbox.ui.components.NavItem
import com.sunnypilot.toolbox.ui.components.SideNavBar
import com.sunnypilot.toolbox.ui.components.TopBar
import com.sunnypilot.toolbox.ui.screens.ConnectionScreen
import com.sunnypilot.toolbox.ui.screens.DataCenterScreen
import com.sunnypilot.toolbox.ui.screens.DeviceDashboardScreen
import com.sunnypilot.toolbox.ui.screens.DeviceManagerScreen
import com.sunnypilot.toolbox.ui.screens.LateralTuneScreen
import com.sunnypilot.toolbox.ui.screens.RecorderScreen
import com.sunnypilot.toolbox.ui.screens.SettingsScreen
import com.sunnypilot.toolbox.ui.screens.TerminalScreen
import com.sunnypilot.toolbox.ui.screens.VideoScreen
import com.sunnypilot.toolbox.ui.screens.FileScreen
import com.sunnypilot.toolbox.ui.theme.Background
import com.sunnypilot.toolbox.ui.theme.Slate100
import com.sunnypilot.toolbox.ui.theme.Slate500
import com.sunnypilot.toolbox.ui.theme.Slate600
import com.sunnypilot.toolbox.ui.theme.SunnyPilotToolboxTheme
import com.sunnypilot.toolbox.ui.theme.Teal500
import kotlinx.coroutines.flow.first

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
    val prefs = remember { context.getSharedPreferences("dpi_settings", Context.MODE_PRIVATE) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var densityScale by remember { mutableFloatStateOf(prefs.getFloat("density_scale", 1.0f)) }

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

        // DPI 密度调节弹窗
        if (showSettingsDialog) {
            DpiSettingsDialog(
                currentScale = densityScale,
                onDismiss = { showSettingsDialog = false },
                onScaleChange = { newScale ->
                    densityScale = newScale
                    prefs.edit().putFloat("density_scale", newScale).apply()
                    showSettingsDialog = false
                }
            )
        }
    }
}

@Composable
private fun DpiSettingsDialog(
    currentScale: Float,
    onDismiss: () -> Unit,
    onScaleChange: (Float) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentScale) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("DPI 密度调节", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "调整界面元素大小，适配不同屏幕密度。\n当前缩放：${String.format("%.0f", sliderValue * 100)}%",
                    fontSize = 14.sp,
                    color = Slate600,
                    lineHeight = 20.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("小", fontSize = 13.sp, color = Slate500)
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 0.5f..1.5f,
                        steps = 19,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Teal500,
                            activeTrackColor = Teal500
                        )
                    )
                    Text("大", fontSize = 13.sp, color = Slate500)
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Slate100,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "提示：调节后立即生效，可随时调整",
                        fontSize = 12.sp,
                        color = Slate500,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onScaleChange(sliderValue)
            }) { Text("确定", color = Teal500) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Slate500) }
        }
    )
}
