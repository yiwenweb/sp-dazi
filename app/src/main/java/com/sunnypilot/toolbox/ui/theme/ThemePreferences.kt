package com.sunnypilot.toolbox.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主题选择持久化。
 *
 * 用 SharedPreferences 保存主题 id，同时用 StateFlow 向 UI 广播变化，
 * 这样切换主题后无需重启 Activity 即可全屏生效。
 */
object ThemePreferences {

    private const val PREFS_NAME = "theme_settings"
    private const val KEY_THEME = "app_theme"

    private val _themeFlow = MutableStateFlow(AppTheme.SYSTEM)
    val themeFlow: StateFlow<AppTheme> = _themeFlow.asStateFlow()

    /** 在 Application / Activity 启动时调用一次，从磁盘恢复上次的主题 */
    fun restore(context: Context) {
        val id = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, AppTheme.SYSTEM.id)
        _themeFlow.value = AppTheme.fromId(id)
    }

    /** 保存并立即应用主题 */
    fun select(context: Context, theme: AppTheme) {
        _themeFlow.value = theme
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .apply()
    }
}
