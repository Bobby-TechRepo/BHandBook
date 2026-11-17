package com.bsoft.bhandbook.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.bsoft.bhandbook.domain.model.ThemeMode

// Deterministic palette (no dynamic colour): ink/background contrast must never depend on the wallpaper.
private val Dark = darkColorScheme(
    primary = Color(0xFF8AB4F8), onPrimary = Color(0xFF062E6F),
    secondary = Color(0xFF81C995), tertiary = Color(0xFFF6AEA9),
    background = Color(0xFF121212), surface = Color(0xFF1E1F22), onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2A2B2F), error = Color(0xFFF28B82),
)
private val Light = lightColorScheme(
    primary = Color(0xFF1A5FD0), onPrimary = Color.White,
    secondary = Color(0xFF1E7B34), tertiary = Color(0xFFB3261E),
    background = Color.White, surface = Color(0xFFF7F8FA), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE7E9EE), error = Color(0xFFB3261E),
)

@Composable
fun BHandBookTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (themeMode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography, content = content)
}
