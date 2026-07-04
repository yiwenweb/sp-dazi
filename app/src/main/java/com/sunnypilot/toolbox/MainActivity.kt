package com.sunnypilot.toolbox

import android.os.Bundle
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
import com.sunnypilot.toolbox.ui.screens.RecorderPlayerScreen
import com.sunnypilot.toolbox.ui.screens.RecorderScreen
import com.sunnypilot.toolbox.ui.screens.TerminalScreen
import com.sunnypilot.toolbox.ui.theme.Background
import com.sunnypilot.toolbox.ui.theme.SunnyPilotToolboxTheme

class MainActivity : ComponentActivity() {
    private val sshManager = SshManager()
    private val configRepository by lazy { ConnectionConfigRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var playerSegment by remember { mutableStateOf<String?>(null) }

    val onConnected: () -> Unit = {
        isConnected = true
        selectedNav = NavItem.Device
    }

    Row(modifier = Modifier.fillMaxSize()) {
        SideNavBar(
            selectedItem = selectedNav,
            onItemSelected = {
                playerSegment = null
                selectedNav = it
            },
            modifier = Modifier.fillMaxHeight()
        )

        Column(modifier = Modifier.weight(1f)) {
            TopBar(
                moduleName = selectedNav.title,
                isConnected = isConnected,
                onRefresh = { isConnected = sshManager.isConnected() },
                onSettings = {}
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
                    val onDisconnected = {
                        sshManager.disconnect()
                        isConnected = false
                        selectedNav = NavItem.Connection
                    }

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
                        NavItem.Recorder -> if (playerSegment != null) {
                            RecorderPlayerScreen(
                                segmentId = playerSegment!!,
                                sshManager = sshManager,
                                onBack = { playerSegment = null }
                            )
                        } else {
                            RecorderScreen(
                                sshManager = sshManager,
                                onPlay = { playerSegment = it }
                            )
                        }
                        else -> DeviceManagerScreen(
                            sshManager = sshManager
                        )
                    }
                }
            }
        }
    }
}
