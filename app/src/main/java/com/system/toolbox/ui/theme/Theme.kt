package com.system.toolbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF),
    onPrimaryContainer = Color(0xFF06206E),
    secondary = Color(0xFF3A5BA0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E2FF),
    onSecondaryContainer = Color(0xFF001945),
    tertiary = Color(0xFF008B82),
    surface = Color(0xFFFDFCFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE2E3EC),
    onSurfaceVariant = Color(0xFF44464F),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF1A1B20),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C7FF),
    onPrimary = Color(0xFF0F2B94),
    primaryContainer = Color(0xFF1C45C8),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFB4C6FF),
    onSecondary = Color(0xFF1C315B),
    secondaryContainer = Color(0xFF334879),
    onSecondaryContainer = Color(0xFFD9E2FF),
    tertiary = Color(0xFF57D9CF),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    background = Color(0xFF0F1014),
    onBackground = Color(0xFFE3E1E9),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

@Composable
fun SystemToolboxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
