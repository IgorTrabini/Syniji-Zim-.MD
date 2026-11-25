package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = Cyan400,
    onPrimary = Slate950,
    primaryContainer = Cyan900,
    onPrimaryContainer = Cyan100,
    secondary = Amber400,
    onSecondary = Slate950,
    secondaryContainer = Amber900,
    onSecondaryContainer = Amber100,
    tertiary = Emerald400,
    onTertiary = Slate950,
    tertiaryContainer = Emerald900,
    onTertiaryContainer = Emerald100,
    error = Rose400,
    onError = Slate950,
    errorContainer = Rose900,
    onErrorContainer = Rose100,
    background = Slate950,
    onBackground = Slate50,
    surface = Slate900,
    onSurface = Slate50,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    surfaceContainerLowest = Slate950,
    surfaceContainerLow = Slate900,
    surfaceContainer = Color(0xFF162033),
    surfaceContainerHigh = Slate800,
    surfaceContainerHighest = Slate700,
    outline = Slate700,
    outlineVariant = Slate800
)

private val LightColorScheme = lightColorScheme(
    primary = Cyan600,
    onPrimary = Color.White,
    primaryContainer = Cyan100,
    onPrimaryContainer = Cyan900,
    secondary = Amber600,
    onSecondary = Color.White,
    secondaryContainer = Amber100,
    onSecondaryContainer = Amber900,
    tertiary = Emerald600,
    onTertiary = Color.White,
    tertiaryContainer = Emerald100,
    onTertiaryContainer = Emerald900,
    error = Rose600,
    onError = Color.White,
    errorContainer = Rose100,
    onErrorContainer = Rose900,
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Slate50,
    surfaceContainer = Slate100,
    surfaceContainerHigh = Slate200,
    surfaceContainerHighest = Color(0xFFCBD5E1),
    outline = Slate400,
    outlineVariant = Slate200
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun SynijiZimTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
