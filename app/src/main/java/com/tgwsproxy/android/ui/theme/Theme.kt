package com.tgwsproxy.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = SignalBlue,
    secondary = BlueGrey40,
    tertiary = SignalMint,
    background = DeepNight,
    surface = NightPanel,
    surfaceVariant = NightPanelAlt,
    outline = NightLine,
    onSurface = NightText,
    onSurfaceVariant = NightMuted,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF1E2837),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD4E5FF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF22252F),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE2E4EB),
    error = androidx.compose.ui.graphics.Color(0xFFFF453A),
)

private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF007AFF),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFEBF3FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF003875),
    secondary = androidx.compose.ui.graphics.Color(0xFF6B7280),
    tertiary = androidx.compose.ui.graphics.Color(0xFF34C759),
    background = androidx.compose.ui.graphics.Color(0xFFF4F5F8),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFECEFF4),
    outline = androidx.compose.ui.graphics.Color(0xFFD6DBE4),
    onSurface = androidx.compose.ui.graphics.Color(0xFF13151A),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF6B7280),
    error = androidx.compose.ui.graphics.Color(0xFFFF3B30),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun TgwsProxyAndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
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
