package com.sunnypilot.toolbox.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.sunnypilot.toolbox.ui.theme.AppColors
import com.sunnypilot.toolbox.ui.theme.AppTheme
import com.sunnypilot.toolbox.ui.theme.LocalAppColors
import com.sunnypilot.toolbox.ui.theme.OnAccent
import com.sunnypilot.toolbox.ui.theme.TextPrimary
import com.sunnypilot.toolbox.ui.theme.TextTertiary
import com.sunnypilot.toolbox.ui.theme.primaryBrush

/**
 * 右上角主题入口按钮：主色渐变圆角块 + 调色板图标。
 */
@Composable
fun ThemePickerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val brush = primaryBrush()
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(brush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Palette,
            contentDescription = "主题外观",
            tint = OnAccent,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 主题选择浮层（锚定右上角）。
 */
@Composable
fun ThemePickerPopup(
    expanded: Boolean,
    currentTheme: AppTheme,
    onDismiss: () -> Unit,
    onSelect: (AppTheme) -> Unit
) {
    if (!expanded) return

    val colors = LocalAppColors.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val offsetX = with(density) { (-28).dp.roundToPx() }
    val offsetY = with(density) { 68.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(offsetX, offsetY),
        properties = PopupProperties(focusable = true),
        onDismissRequest = onDismiss
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = colors.panel),
            border = BorderStroke(1.dp, colors.panelBorder),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            modifier = Modifier
                .width(368.dp)
                .padding(8.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // ── 标题 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(primaryBrush()),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = OnAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "主题外观",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "选择一套界面配色，自动保存",
                            fontSize = 11.sp,
                            color = TextTertiary
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                AppTheme.values().forEachIndexed { index, theme ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    ThemeOptionCard(
                        theme = theme,
                        selected = theme == currentTheme,
                        onSelect = { onSelect(theme) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionCard(
    theme: AppTheme,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val c = theme.colors
    val borderColor = if (selected) c.primary else Color.Transparent

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.primarySoft else c.softSurface)
            .border(width = 1.5.dp, color = borderColor, shape = RoundedCornerShape(18.dp))
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        ThemePreviewThumb(colors = c)

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = theme.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = c.textPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = theme.subtitle,
                fontSize = 11.sp,
                color = c.textTertiary
            )
            Spacer(Modifier.height(7.dp))
            // 色板圆点
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(c.primary, c.accent2, c.success, c.warning).forEach { dot ->
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(dot)
                    )
                }
            }
        }

        // 选中指示
        if (selected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(c.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "当前主题",
                    tint = c.onAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, c.dividerStrong, CircleShape)
            )
        }
    }
}

/** 迷你界面预览：左侧导航 + 顶栏 + 卡片骨架 */
@Composable
private fun ThemePreviewThumb(colors: AppColors) {
    Box(
        modifier = Modifier
            .size(width = 92.dp, height = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(colors.previewGradient))
            .border(1.dp, colors.panelBorder, RoundedCornerShape(12.dp))
    ) {
        Row(modifier = Modifier.fillMaxHeight()) {
            // 侧边导航
            Box(
                modifier = Modifier
                    .width(20.dp)
                    .fillMaxHeight()
                    .background(colors.panel.copy(alpha = 0.55f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 12.dp, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.primary)
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 12.dp, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.textPrimary.copy(alpha = 0.25f))
                    )
                    Box(
                        modifier = Modifier
                            .size(width = 12.dp, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.textPrimary.copy(alpha = 0.18f))
                    )
                }
            }
            // 内容区
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 30.dp, height = 7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.primary.copy(alpha = 0.9f))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.panel.copy(alpha = 0.72f))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.panel.copy(alpha = 0.55f))
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(colors.accent2.copy(alpha = 0.55f))
                    )
                }
            }
        }
    }
}
