package com.sunnypilot.toolbox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sunnypilot.toolbox.ui.theme.*

enum class NavItem(val title: String, val icon: ImageVector, val finished: Boolean = false) {
    Connection("连接中心", Icons.Default.Link, true),
    Device("设备管家", Icons.Default.Build, true),
    Hardware("硬件管理", Icons.Default.Devices, true),
    Terminal("终端", Icons.Default.Terminal, true),
    Data("数据中心", Icons.Default.BarChart, true),
    Recorder("记录仪预览", Icons.Default.VideoLibrary, true),
    Video("视频预览", Icons.Default.Videocam, true),
    Video2("超级视频2", Icons.Default.CastConnected, true),
    Files("文件", Icons.Default.Folder, true),
    Calc("智能计算", Icons.Default.Calculate, true),
    Tune("横向调参", Icons.Default.Tune, true),
    Radar("雷达导航", Icons.Default.Radar, true),
    Customize("个性化", Icons.Default.Palette, true),
    Shortcuts("一键下发", Icons.Default.Send),
    Share("分享中心", Icons.Default.Share),
    Backup("备份", Icons.Default.CloudUpload),
    Flash("恢复刷机", Icons.Default.SystemUpdate),
    Feedback("需求中心", Icons.Default.Feedback),
    Info("信息中心", Icons.Default.Info),
    Config("设置", Icons.Default.SettingsApplications),
    About("关于", Icons.Default.Help)
}

private val NavShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)

@Composable
fun SideNavBar(
    selectedItem: NavItem,
    onItemSelected: (NavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    val finishedItems = NavItem.values().filter { it.finished }
    val pendingItems = NavItem.values().filter { !it.finished }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(88.dp)
            .clip(NavShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.navSurface,
                        colors.navSurface.copy(alpha = colors.navSurface.alpha * 0.72f)
                    )
                )
            )
            .border(BorderStroke(1.dp, colors.panelBorder), NavShape)
            .padding(vertical = 14.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            // 品牌标记
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(colors.primaryGradient)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = "SunnyPilot",
                    tint = colors.onAccent,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            finishedItems.forEach { item ->
                NavButton(
                    item = item,
                    selected = item == selectedItem,
                    onClick = { onItemSelected(item) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            if (pendingItems.isNotEmpty()) {
                Divider(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                    color = DividerColor
                )
            }

            pendingItems.forEach { item ->
                NavButton(
                    item = item,
                    selected = item == selectedItem,
                    onClick = { }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun NavButton(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    val enabled = item.finished
    val contentColor = when {
        !enabled -> TextTertiary
        selected -> colors.primary
        else -> TextSecondary
    }
    val pillBrush = Brush.verticalGradient(
        listOf(colors.primarySoft, colors.accent2Soft)
    )
    val borderColor = if (selected) colors.primary.copy(alpha = 0.45f) else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.background(pillBrush) else Modifier)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.title,
            tint = contentColor,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) TextPrimary else contentColor,
            textAlign = TextAlign.Center
        )
    }
}
