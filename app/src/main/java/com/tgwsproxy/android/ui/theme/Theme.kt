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
    primary = SignalCyan,
    secondary = SignalBlue,
    tertiary = SignalMint,
    background = DeepNight,
    surface = NightPanel,
    surfaceVariant = NightPanelAlt,
    outline = NightLine,
    onSurface = NightText,
    onSurfaceVariant = NightMuted,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF103545),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFDDF9FF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF252D5A),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE8EBFF),
    error = androidx.compose.ui.graphics.Color(0xFFFF7D8D),
)

private val LightColorScheme = lightColorScheme(
    primary = Teal40,
    secondary = BlueGrey40,
    tertiary = Amber40,
    background = androidx.compose.ui.graphics.Color(0xFFF4FAFA),
    surface = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE4F1F2),
    onSurface = androidx.compose.ui.graphics.Color(0xFF15282C),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF546B71),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
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
