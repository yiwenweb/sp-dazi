package com.sunnypilot.toolbox.ui.theme

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 应用根主题。
 *
 * @param theme 当前选中的主题，默认跟随系统（浅色）
 */
@Composable
fun SunnyPilotToolboxTheme(
    theme: AppTheme = AppTheme.SYSTEM,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = rememberColorScheme(theme),
        typography = Typography
    ) {
        // 主题切换时做一次淡入淡出，避免整屏颜色瞬变
        Crossfade(
            targetState = theme,
            animationSpec = tween(durationMillis = 280),
            label = "themeCrossfade"
        ) { currentTheme ->
            val colors = currentTheme.colors
            CompositionLocalProvider(LocalAppColors provides colors) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(appBackgroundBrush(colors))
                        .drawBehind {
                            // 右上角氛围光晕
                            drawRect(
                                brush = Brush.radialGradient(
                                    colors = listOf(colors.bgGlow, Color.Transparent),
                                    center = Offset(size.width * 0.88f, -size.height * 0.12f),
                                    radius = size.width * 0.85f
                                )
                            )
                        }
                ) {
                    content()
                }
            }
        }
    }
}

/** 主题背景渐变（纵向） */
@Composable
fun appBackgroundBrush(colors: AppColors = LocalAppColors.current): Brush =
    Brush.verticalGradient(listOf(colors.bgTop, colors.bgMid, colors.bgBottom))

/** 主色渐变（按钮 / 选中态 / 图标底） */
@Composable
fun primaryBrush(colors: AppColors = LocalAppColors.current): Brush =
    Brush.linearGradient(colors.primaryGradient)

/** 面板渐变（卡片轻微高光，深色主题下更有质感） */
@Composable
fun panelBrush(colors: AppColors = LocalAppColors.current): Brush =
    Brush.verticalGradient(
        listOf(
            colors.panel.copy(alpha = if (colors.isDark) 0.96f else 1f),
            colors.panelAlt.copy(alpha = if (colors.isDark) 0.9f else 1f)
        )
    )

/** 卡片统一阴影高度 */
val CardElevation = 2.dp

/**
 * Material3 配色：让系统级组件（Dialog / TextField / DropdownMenu / Switch）
 * 也跟随主题。
 */
@Composable
private fun rememberColorScheme(theme: AppTheme) =
    remember(theme) { buildColorScheme(theme.colors) }

private fun buildColorScheme(c: AppColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.primary,
        onPrimary = c.onAccent,
        primaryContainer = c.primarySoft,
        onPrimaryContainer = c.primary,
        secondary = c.accent2,
        onSecondary = c.onAccent,
        secondaryContainer = c.accent2Soft,
        onSecondaryContainer = c.accent2,
        tertiary = c.accent3,
        onTertiary = c.onAccent,
        background = c.bgMid,
        onBackground = c.textPrimary,
        surface = c.panel,
        onSurface = c.textPrimary,
        surfaceVariant = c.softSurface,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = c.primary,
        outline = c.panelBorder,
        outlineVariant = c.divider,
        error = c.danger,
        onError = c.onAccent,
        errorContainer = c.dangerSoft,
        onErrorContainer = c.danger
    )
} else {
    lightColorScheme(
        primary = c.primary,
        onPrimary = Color.White,
        primaryContainer = c.primarySoft,
        onPrimaryContainer = c.primaryStrong,
        secondary = c.accent2,
        onSecondary = Color.White,
        secondaryContainer = c.accent2Soft,
        onSecondaryContainer = c.accent2,
        tertiary = c.accent3,
        onTertiary = Color.White,
        background = c.bgTop,
        onBackground = c.textPrimary,
        surface = c.cardSurface,
        onSurface = c.textPrimary,
        surfaceVariant = c.softSurface,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = c.primary,
        outline = c.panelBorder,
        outlineVariant = c.divider,
        error = c.danger,
        onError = Color.White,
        errorContainer = c.dangerSoft,
        onErrorContainer = c.dangerStrong
    )
}
