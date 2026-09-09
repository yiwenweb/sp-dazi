package com.sunnypilot.toolbox.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 全局扩展配色。
 *
 * 应用内所有界面都通过 `LocalAppColors` 取色，切换主题时无需修改任何业务代码。
 * 旧的语义色（Tealxxx / Slatexxx / Amberxxx ...）在 Color.kt 中被桥接到这里，
 * 因此历史界面代码零改动即可跟随主题变化。
 */
data class AppColors(
    val isDark: Boolean,

    // ── 背景 ──
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    /** 背景光晕（带透明度），用于径向渐变氛围光 */
    val bgGlow: Color,
    /**
     * 次氛围光（左下角）。默认无；毛玻璃这类需要「有色衬底」的主题才设置，
     * 让半透明面板有颜色可透，否则玻璃叠在纯暗底上会发灰。
     */
    val bgGlow2: Color = Color.Transparent,

    // ── 面板 / 卡片 ──
    val panel: Color,
    val panelAlt: Color,
    val panelBorder: Color,
    val softSurface: Color,
    val softSurfaceStrong: Color,
    /** 卡片/浮层底色（历史代码中的 Color.White） */
    val cardSurface: Color,
    /** 导航栏玻璃底色（半透明） */
    val navSurface: Color,

    // ── 文本 ──
    val textPrimary: Color,
    val textStrong: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textMuted: Color,

    // ── 分割线 ──
    val divider: Color,
    val dividerStrong: Color,

    // ── 主色 / 强调色 ──
    val primary: Color,
    val primaryStrong: Color,
    val primarySoft: Color,
    /** 落在主色块上的文字/图标色 */
    val onAccent: Color,
    val accent2: Color,
    val accent2Soft: Color,
    val accent3: Color,
    val accent3Soft: Color,
    /** 主色渐变（按钮、选中态、图标底） */
    val primaryGradient: List<Color>,

    // ── 语义色 ──
    val success: Color,
    val successStrong: Color,
    val successSoft: Color,
    val warning: Color,
    val warningStrong: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerStrong: Color,
    val dangerSoft: Color,
    val info: Color,
    val infoSoft: Color,

    // ── 主题选择器预览渐变 ──
    val previewGradient: List<Color>
)

// region ─────────────────────────── 调色板 ───────────────────────────

/** ① 系统默认：清爽浅色，原厂 Teal / Slate 配色 */
private val SystemColors = AppColors(
    isDark = false,
    bgTop = Color(0xFFF8FAFC),
    bgMid = Color(0xFFF1F5F9),
    bgBottom = Color(0xFFE9EFF7),
    bgGlow = Color(0x339CCBF1),

    panel = Color(0xFFFFFFFF),
    panelAlt = Color(0xFFF8FAFC),
    panelBorder = Color(0xFFE2E8F0),
    softSurface = Color(0xFFF1F5F9),
    softSurfaceStrong = Color(0xFFE2E8F0),
    cardSurface = Color(0xFFFFFFFF),
    navSurface = Color(0xF2FFFFFF),

    textPrimary = Color(0xFF0F172A),
    textStrong = Color(0xFF1E293B),
    textSecondary = Color(0xFF475569),
    textTertiary = Color(0xFF64748B),
    textMuted = Color(0xFF94A3B8),

    divider = Color(0xFFE2E8F0),
    dividerStrong = Color(0xFFCBD5E1),

    primary = Color(0xFF0D9488),
    primaryStrong = Color(0xFF0F766E),
    primarySoft = Color(0xFFF0FDFA),
    onAccent = Color(0xFFFFFFFF),
    accent2 = Color(0xFF8B5CF6),
    accent2Soft = Color(0xFFEDE9FE),
    accent3 = Color(0xFFF97316),
    accent3Soft = Color(0xFFFFEDD5),
    primaryGradient = listOf(Color(0xFF14B8A6), Color(0xFF0D9488)),

    success = Color(0xFF22C55E),
    successStrong = Color(0xFF16A34A),
    successSoft = Color(0xFFDCFCE7),
    warning = Color(0xFFF59E0B),
    warningStrong = Color(0xFFD97706),
    warningSoft = Color(0xFFFEF3C7),
    danger = Color(0xFFEF4444),
    dangerStrong = Color(0xFFDC2626),
    dangerSoft = Color(0xFFFEE2E2),
    info = Color(0xFF3B82F6),
    infoSoft = Color(0xFFDBEAFE),

    previewGradient = listOf(Color(0xFFF8FAFC), Color(0xFFCCFBF1), Color(0xFF0D9488))
)

/** ② 曜石深空：深空蓝底 + 电光青霓虹，夜间行车不刺眼 */
private val ObsidianColors = AppColors(
    isDark = true,
    bgTop = Color(0xFF060A12),
    bgMid = Color(0xFF0B1220),
    bgBottom = Color(0xFF101A2C),
    bgGlow = Color(0x4022D3EE),

    panel = Color(0xFF121B2B),
    panelAlt = Color(0xFF17223A),
    panelBorder = Color(0xFF243350),
    softSurface = Color(0xFF18233A),
    softSurfaceStrong = Color(0xFF22304C),
    cardSurface = Color(0xFF121B2B),
    navSurface = Color(0xD9121B2B),

    textPrimary = Color(0xFFE8EFFB),
    textStrong = Color(0xFFD3DDF0),
    textSecondary = Color(0xFF9BAACD),
    textTertiary = Color(0xFF7C8CAC),
    textMuted = Color(0xFF5A6B87),

    divider = Color(0xFF1F2C45),
    dividerStrong = Color(0xFF2C3C5C),

    primary = Color(0xFF22D3EE),
    primaryStrong = Color(0xFF06B6D4),
    primarySoft = Color(0xFF0E3444),
    onAccent = Color(0xFF04121A),
    accent2 = Color(0xFFA78BFA),
    accent2Soft = Color(0xFF272154),
    accent3 = Color(0xFFFB923C),
    accent3Soft = Color(0xFF3A2415),
    primaryGradient = listOf(Color(0xFF22D3EE), Color(0xFF7C8CFC), Color(0xFFA78BFA)),

    success = Color(0xFF34D399),
    successStrong = Color(0xFF10B981),
    successSoft = Color(0xFF10362C),
    warning = Color(0xFFFBBF24),
    warningStrong = Color(0xFFF59E0B),
    warningSoft = Color(0xFF3A2C10),
    danger = Color(0xFFFB7185),
    dangerStrong = Color(0xFFF43F5E),
    dangerSoft = Color(0xFF3B1622),
    info = Color(0xFF60A5FA),
    infoSoft = Color(0xFF132B4A),

    previewGradient = listOf(Color(0xFF060A12), Color(0xFF0E4F63), Color(0xFF6D28D9))
)

/** ③ 琥珀机械：暖金橙机械质感，贴合暖色内饰 */
private val EmberColors = AppColors(
    isDark = true,
    bgTop = Color(0xFF100C07),
    bgMid = Color(0xFF181209),
    bgBottom = Color(0xFF221910),
    bgGlow = Color(0x40FFB020),

    panel = Color(0xFF1E1710),
    panelAlt = Color(0xFF241B12),
    panelBorder = Color(0xFF3A2C1B),
    softSurface = Color(0xFF261D13),
    softSurfaceStrong = Color(0xFF33271A),
    cardSurface = Color(0xFF1E1710),
    navSurface = Color(0xD91E1710),

    textPrimary = Color(0xFFF8EFDF),
    textStrong = Color(0xFFEADCC6),
    textSecondary = Color(0xFFBCA98C),
    textTertiary = Color(0xFF9A8870),
    textMuted = Color(0xFF75654E),

    divider = Color(0xFF33271A),
    dividerStrong = Color(0xFF42321F),

    primary = Color(0xFFFFB020),
    primaryStrong = Color(0xFFF59E0B),
    primarySoft = Color(0xFF3A2A0E),
    onAccent = Color(0xFF1A1206),
    accent2 = Color(0xFFFF6B35),
    accent2Soft = Color(0xFF3D1E12),
    accent3 = Color(0xFFFFC857),
    accent3Soft = Color(0xFF3A2E12),
    primaryGradient = listOf(Color(0xFFFFC857), Color(0xFFFFB020), Color(0xFFFF6B35)),

    success = Color(0xFFA3E635),
    successStrong = Color(0xFF84CC16),
    successSoft = Color(0xFF26320F),
    warning = Color(0xFFFFB020),
    warningStrong = Color(0xFFFB923C),
    warningSoft = Color(0xFF3A2A0E),
    danger = Color(0xFFFF6B6B),
    dangerStrong = Color(0xFFEF4444),
    dangerSoft = Color(0xFF3B1717),
    info = Color(0xFF7DD3FC),
    infoSoft = Color(0xFF12303B),

    previewGradient = listOf(Color(0xFF100C07), Color(0xFF7C2D12), Color(0xFFFFB020))
)

/**
 * ④ 晶透玻璃：iOS 26「Liquid Glass」风格
 *
 * 实现思路：面板 / 卡片 / 导航全部使用**半透明白**（15%~20%），
 * 直接叠在根布局的深空蓝紫渐变上，透出底层色彩 —— 这就是可移植的毛玻璃
 * （真模糊 RenderEffect 需要 API 31，车机普遍是 Android 7~10，用不了）。
 * 描边用半透明白模拟玻璃高光边缘；强调色取 iOS 系统色 蓝 / 青 / 紫。
 */
private val GlassColors = AppColors(
    isDark = true,
    bgTop = Color(0xFF050813),
    bgMid = Color(0xFF0A1230),
    bgBottom = Color(0xFF1B0F3E),
    bgGlow = Color(0x735EA8FF),
    bgGlow2 = Color(0x59BF5AF2),

    panel = Color(0x2EFFFFFF),
    panelAlt = Color(0x21FFFFFF),
    panelBorder = Color(0x47FFFFFF),
    softSurface = Color(0x1AFFFFFF),
    softSurfaceStrong = Color(0x30FFFFFF),
    cardSurface = Color(0x26FFFFFF),
    navSurface = Color(0x1CFFFFFF),

    textPrimary = Color(0xFFFFFFFF),
    textStrong = Color(0xFFF3F7FF),
    textSecondary = Color(0xFFC6D5F3),
    textTertiary = Color(0xFFA3B6DC),
    textMuted = Color(0xFF7E91BA),

    divider = Color(0x26FFFFFF),
    dividerStrong = Color(0x45FFFFFF),

    primary = Color(0xFF64D2FF),
    primaryStrong = Color(0xFF0A84FF),
    primarySoft = Color(0x360A84FF),
    onAccent = Color(0xFF03101F),
    accent2 = Color(0xFFBF5AF2),
    accent2Soft = Color(0x36BF5AF2),
    accent3 = Color(0xFF5EE6C6),
    accent3Soft = Color(0x2E5EE6C6),
    primaryGradient = listOf(Color(0xFF7EE0FF), Color(0xFF0A84FF), Color(0xFFBF5AF2)),

    success = Color(0xFF30D158),
    successStrong = Color(0xFF248A3D),
    successSoft = Color(0x2E30D158),
    warning = Color(0xFFFF9F0A),
    warningStrong = Color(0xFFC77C08),
    warningSoft = Color(0x30FF9F0A),
    danger = Color(0xFFFF453A),
    dangerStrong = Color(0xFFD6342B),
    dangerSoft = Color(0x30FF453A),
    info = Color(0xFF64D2FF),
    infoSoft = Color(0x2E64D2FF),

    previewGradient = listOf(Color(0xFF050813), Color(0xFF0A84FF), Color(0xFFBF5AF2))
)

// endregion

/** 主题标识，与持久化存储中的 id 一一对应 */
enum class AppTheme(val id: String, val label: String, val subtitle: String, val emoji: String) {
    SYSTEM("system", "系统默认", "清爽浅色 · 原厂配色", "◻"),
    OBSIDIAN("obsidian", "曜石深空", "深空蓝 · 电光青霓虹", "◆"),
    EMBER("ember", "琥珀机械", "暖金橙 · 机械质感", "❖"),
    GLASS("glass", "晶透玻璃", "iOS 液景 · 半透叠层", "◍");

    val colors: AppColors
        get() = when (this) {
            SYSTEM -> SystemColors
            OBSIDIAN -> ObsidianColors
            EMBER -> EmberColors
            GLASS -> GlassColors
        }

    val isDark: Boolean get() = colors.isDark

    companion object {
        fun fromId(id: String?): AppTheme =
            values().firstOrNull { it.id == id } ?: SYSTEM
    }
}

val LocalAppColors = staticCompositionLocalOf { SystemColors }
