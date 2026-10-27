package com.ttech.pocketlinux.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF3DDC84)
private val GreenDark = Color(0xFF0B7A45)

private val DarkColors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF00391C),
    primaryContainer = Color(0xFF0F5132),
    onPrimaryContainer = Color(0xFFB7F5CF),
    secondary = Color(0xFF9CCFB3),
    background = Color(0xFF101412),
    surface = Color(0xFF101412),
    surfaceVariant = Color(0xFF1E2622),
    surfaceContainer = Color(0xFF18201C),
    surfaceContainerHigh = Color(0xFF212A25),
)

private val LightColors = lightColorScheme(
    primary = GreenDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F5CF),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6356),
)

@Composable
fun PocketLinuxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** ターミナルの背景（TerminalView の既定の配色に合わせる） */
val TerminalBackground = Color(0xFF000000)
val ExtraKeysBackground = Color(0xFF1B1B1B)
