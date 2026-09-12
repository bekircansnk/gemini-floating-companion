package com.gemini.floatingcompanion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = GeminiBlue,
    secondary = GeminiPurple,
    tertiary = GeminiCyan,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceElevated,
    onPrimary = TextPrimaryDark,
    onSecondary = TextPrimaryDark,
    onBackground = TextPrimaryDark,
    onSurface = TextPrimaryDark
)

private val LightColorScheme = lightColorScheme(
    primary = GeminiBlue,
    secondary = GeminiPurple,
    tertiary = GeminiCyan,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceElevated,
    onPrimary = LightSurface,
    onSecondary = LightSurface,
    onBackground = TextPrimaryLight,
    onSurface = TextPrimaryLight
)

@Composable
fun GeminiCompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
