package com.shizuku.speedtest.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF45699D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7F5),
    onPrimaryContainer = Color(0xFF1B3350),
    secondary = Color(0xFF5B6F8C),
    secondaryContainer = Color(0xFFDDE7F0),
    onSecondaryContainer = Color(0xFF1B3350),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF1F1F4),
    onSurface = Color(0xFF1C202E),
    surfaceVariant = Color(0xFFE4E7EB),
    onSurfaceVariant = Color(0xFF5F6578),
    outline = Color(0xFFDADDE0)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5B82C2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2C457D),
    onPrimaryContainer = Color(0xFFD6E4F7),
    secondary = Color(0xFFA8BBD0),
    secondaryContainer = Color(0xFF334057),
    onSecondaryContainer = Color(0xFFD6E4F7),
    background = Color(0xFF16181A),
    surface = Color(0xFF24272B),
    onSurface = Color(0xFFE9ECF0),
    surfaceVariant = Color(0xFF2F3438),
    onSurfaceVariant = Color(0xFFA7B1BD),
    outline = Color(0xFF3C4348)
)

private val AppShapes = Shapes(
    medium = RoundedCornerShape(28.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun SpeedTestTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = remember(darkTheme, dynamicColor) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content
    )
}
