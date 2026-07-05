package com.sunnypilot.toolbox

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.sunnypilot.toolbox.ui.theme.Background
import com.sunnypilot.toolbox.ui.theme.SunnyPilotToolboxTheme
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
                onSettings = {},
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
                        else -> DeviceManagerScreen(
                            sshManager = sshManager
                        )
                    }
                }
            }
        }
    }
}
