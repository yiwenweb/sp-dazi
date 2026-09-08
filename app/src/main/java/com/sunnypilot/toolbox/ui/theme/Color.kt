package com.sunnypilot.toolbox.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/*
 * 语义色桥接层
 * ------------------------------------------------------------------
 * 历史代码里大量使用 Teal500 / Slate900 / Amber500 等具名颜色，
 * 为了让这些界面「零改动」跟随主题切换，这里把每个具名颜色改写成
 * 从 LocalAppColors 读取的 @Composable 属性。
 *
 * 约束：只能在 @Composable 作用域内使用（DrawScope / remember{} 等
 * 非组合作用域需要先在组合内取出再传入）。
 */

// ── 主色系 ──
val Teal500: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.primary

val Teal700: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.primaryStrong

val Teal50: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.primarySoft

val Teal100: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.primarySoft

// ── 中性色阶（主要用作文本，层级由深到浅）──
val Slate900: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textPrimary

val Slate700: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textStrong

val Slate600: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textSecondary

val Slate500: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textTertiary

val Slate400: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textMuted

val Slate300: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.dividerStrong

val Slate200: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.divider

val Slate100: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.softSurfaceStrong

val Slate50: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.softSurface

// ── 容器 ──
val Background: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.bgTop

val Panel: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.panel

// ── 琥珀 / 警告 ──
val Amber600: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warningStrong

val Amber500: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning

val Amber100: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warningSoft

val Amber50: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warningSoft

// ── 红 / 危险 ──
val Red500: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.danger

val Red600: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.dangerStrong

val Red100: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.dangerSoft

val Red50: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.dangerSoft

// ── 绿 / 成功 ──
val Green500: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.success

val Green600: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.successStrong

val Green700: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.successStrong

val Green100: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.successSoft

val Green50: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.successSoft

// ── 蓝 / 信息 ──
val Blue500: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.info

val Blue100: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.infoSoft

// ── 紫 / 第二强调 ──
val Purple500: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent2

val Purple100: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent2Soft

// ── 橙 / 第三强调 ──
val Orange500: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent3

val Orange100: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.accent3Soft

/*
 * 新增语义色（推荐新代码使用）
 */

/** 卡片 / 浮层底色：替代硬编码 Color.White */
val CardSurface: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.cardSurface

/** 落在强调色块（主色按钮、选中胶囊）上的文字与图标色 */
val OnAccent: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.onAccent

/** 轻微抬升的底色（列表行、输入栏） */
val SoftSurface: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.softSurface

/** 更强的抬升底色 */
val SoftSurfaceStrong: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.softSurfaceStrong

/** 面板描边 */
val PanelBorder: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.panelBorder

/** 导航栏玻璃底色 */
val NavSurface: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.navSurface

/** 一级文本 */
val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textPrimary

/** 二级文本 */
val TextSecondary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textSecondary

/** 三级 / 弱化文本 */
val TextTertiary: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.textTertiary

/** 分割线 */
val DividerColor: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.divider

/** 实时 HUD（行车记录仪播放器）固定深色底，不随主题变化 */
val HudBackdrop = Color(0xFF0B1220)
