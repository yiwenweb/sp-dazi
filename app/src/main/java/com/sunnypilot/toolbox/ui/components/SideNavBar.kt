package com.sunnypilot.toolbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.sunnypilot.toolbox.ui.theme.*

enum class NavItem(val title: String, val icon: ImageVector) {
    Connection("连接中心", Icons.Default.Link),
    Hardware("硬件管理", Icons.Default.Devices),
    Device("设备管家", Icons.Default.Build),
    Terminal("终端", Icons.Default.Terminal),
    Video("视频预览", Icons.Default.Videocam),
    Recorder("记录仪预览", Icons.Default.VideoLibrary),
    Files("文件", Icons.Default.Folder),
    Calc("智能计算", Icons.Default.Calculate),
    Data("数据中台", Icons.Default.BarChart),
    Settings("驾驶设置", Icons.Default.Settings),
    Shortcuts("一键下发", Icons.Default.Send),
    Share("分享中心", Icons.Default.Share),
    Backup("备份", Icons.Default.CloudUpload),
    Flash("恢复刷机", Icons.Default.SystemUpdate),
    Feedback("需求中心", Icons.Default.Feedback),
    Info("信息中心", Icons.Default.Info),
    Config("设置", Icons.Default.SettingsApplications),
    About("关于", Icons.Default.Help)
}

@Composable
fun SideNavBar(
    selectedItem: NavItem,
    onItemSelected: (NavItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        color = Background,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxHeight()
            .width(88.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            NavItem.values().forEach { item ->
                NavButton(
                    item = item,
                    selected = item == selectedItem,
                    onClick = { onItemSelected(item) }
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Teal50 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.title,
            tint = if (selected) Teal500 else Slate600,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Teal500 else Slate600,
            textAlign = TextAlign.Center
        )
    }
}
