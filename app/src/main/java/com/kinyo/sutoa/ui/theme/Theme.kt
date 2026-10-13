package com.kinyo.sutoa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = GreenPrimary,
    onPrimary = GreenOnPrimary,
    primaryContainer = GreenContainer,
    onPrimaryContainer = OnGreenContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealContainer,
    onSecondaryContainer = OnTealContainer,
    tertiary = AmberTertiary,
    onTertiary = AmberOnTertiary,
    background = BgDark,
    onBackground = OnBgDark,
    surface = SurfaceDark,
    onSurface = OnBgDark,
    surfaceVariant = SurfaceVariantDark,
    error = ErrorDark,
    onError = onErrorDark,
)

private val LightColors = lightColorScheme(
    primary = GreenContainer,
    onPrimary = OnGreenContainer,
    secondary = TealContainer,
    tertiary = AmberOnTertiary,
)

/**
 * MVP: dark theme is the default. [darkTheme] defaults to following the system,
 * but callers (MainActivity) pass `true` unless explicitly opted into light.
 */
@Composable
fun SutoaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
