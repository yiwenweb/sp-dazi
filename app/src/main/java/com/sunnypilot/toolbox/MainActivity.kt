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
import com.sunnypilot.toolbox.ui.screens.DeviceDashboardScreen
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

    Row(modifier = Modifier.fillMaxSize()) {
        SideNavBar(
            selectedItem = selectedNav,
            onItemSelected = { selectedNav = it },
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
                when (selectedNav) {
                    NavItem.Connection -> ConnectionScreen(
                        sshManager = sshManager,
                        repository = configRepository,
                        onConnected = { isConnected = true }
                    )
                    NavItem.Device -> DeviceDashboardScreen(sshManager = sshManager)
                    else -> ConnectionScreen(
                        sshManager = sshManager,
                        repository = configRepository,
                        onConnected = { isConnected = true }
                    )
                }
            }
        }
    }
}
