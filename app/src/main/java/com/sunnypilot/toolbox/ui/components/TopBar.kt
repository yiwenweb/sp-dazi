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
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
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
                    color = Slate600
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
                    color = Color.White,
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
                    IconButton(
                        onClick = onDisconnect,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LinkOff,
                            contentDescription = "断开连接",
                            tint = Red500
                        )
                    }
                }

                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "刷新",
                        tint = Slate600
                    )
                }

                IconButton(
                    onClick = onSettings,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = Slate600
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Background,
            titleContentColor = Slate900
        )
    )
}

@Composable
private fun DeviceBadge(isConnected: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White,
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
                color = Slate900
            )
        }
    }
}

@Composable
private fun ConnectionPath() {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.White,
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
            color = if (active) Slate900 else Slate400
        )
    }
}

@Composable
fun StatusChip(text: String, isActive: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (isActive) Green100 else Slate100,
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
                    .background(if (isActive) Green500 else Slate400)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) Slate900 else Slate600
            )
        }
    }
}
