package com.sunnypilot.toolbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Teal500,
    onPrimary = Color.White,
    primaryContainer = Teal50,
    onPrimaryContainer = Teal700,
    secondary = Amber500,
    background = Background,
    onBackground = Slate900,
    surface = Panel,
    onSurface = Slate900,
    outline = Slate200
)

private val DarkColors = darkColorScheme(
    primary = Teal500,
    onPrimary = Color.White,
    background = Slate900,
    onBackground = Color.White,
    surface = Slate700,
    onSurface = Color.White
)

@Composable
fun SunnyPilotToolboxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
