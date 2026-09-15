package com.sensorstream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

/** Provides the premium [SsColors] palette to the tree; read via [Ss.colors]. */
val LocalSsColors = staticCompositionLocalOf { darkSsColors() }

object Ss {
    val colors: SsColors
        @Composable get() = LocalSsColors.current
    val dims: SsDims get() = SsDims
}

@Composable
fun SensorStreamTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) darkSsColors() else lightSsColors()
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent,
            background = c.bg,
            surface = c.surface,
            onSurface = c.fg,
            onBackground = c.fg,
            error = c.err,
        )
    } else {
        lightColorScheme(
            primary = c.accent,
            background = c.bg,
            surface = c.surface,
            onSurface = c.fg,
            onBackground = c.fg,
            error = c.err,
        )
    }
    CompositionLocalProvider(LocalSsColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = ssTypography(), content = content)
    }
}
