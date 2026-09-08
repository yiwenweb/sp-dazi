package com.sunnypilot.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopBar(
    moduleName: String,
    isConnected: Boolean,
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    var showThemePicker by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DeviceBadge(isConnected)
                ConnectionPath()
                Text(
                    text = "当前模块：$moduleName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        },
        actions = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = CardSurface,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = Amber500,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "命令桥",
                            style = MaterialTheme.typography.labelLarge,
                            color = Amber500
                        )
                    }
                }

                StatusChip(
                    text = if (isConnected) "已连接" else "未连接",
                    isActive = isConnected
                )

                if (isConnected) {
                    TopBarIconButton(onClick = onDisconnect) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = "断开连接",
                            tint = Red500
                        )
                    }
                }

                TopBarIconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新",
                        tint = TextSecondary
                    )
                }

                TopBarIconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = TextSecondary
                    )
                }

                // ── 主题入口（右上角）──
                ThemePickerButton(onClick = { showThemePicker = true })
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = TextPrimary
        )
    )

    ThemePickerPopup(
        expanded = showThemePicker,
        currentTheme = currentTheme,
        onDismiss = { showThemePicker = false },
        onSelect = { theme ->
            onThemeSelected(theme)
            showThemePicker = false
        }
    )
}

@Composable
private fun TopBarIconButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
    ) {
        content()
    }
}

@Composable
private fun DeviceBadge(isConnected: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = CardSurface,
        shadowElevation = 2.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Green500 else Slate400)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Comma C3",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun ConnectionPath() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = CardSurface,
        shadowElevation = 2.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            PathItem("App", true)
            Text("→", color = Slate400, modifier = Modifier.padding(horizontal = 4.dp))
            PathItem("Wi-Fi", true)
            Text("→", color = Slate400, modifier = Modifier.padding(horizontal = 4.dp))
            PathItem("设备", true)
        }
    }
}

@Composable
private fun PathItem(text: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) Teal500 else Slate400)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) TextPrimary else Slate400
        )
    }
}

@Composable
fun StatusChip(text: String, isActive: Boolean) {
    val bg = if (isActive) LocalAppColors.current.successSoft else SoftSurface
    val dot = if (isActive) Green500 else Slate400
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = bg,
        shadowElevation = 0.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) TextPrimary else TextSecondary
            )
        }
    }
}
