package com.tgwsproxy.android.ui.theme

import android.app.Activity
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
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = SignalBlue,
    onPrimary = Color.White,
    secondary = BlueGrey40,
    tertiary = SignalMint,
    background = DeepNight,
    onBackground = NightText,
    surface = NightPanel,
    surfaceVariant = NightPanelAlt,
    outline = NightLine,
    onSurface = NightText,
    onSurfaceVariant = NightMuted,
    primaryContainer = Color(0xFF1E2837),
    onPrimaryContainer = Color(0xFFD4E5FF),
    secondaryContainer = Color(0xFF22252F),
    onSecondaryContainer = Color(0xFFE2E4EB),
    error = Color(0xFFFF453A),
    errorContainer = Color(0xFF3B1416),
    onErrorContainer = Color(0xFFFFB4AB),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBF3FF),
    onPrimaryContainer = Color(0xFF003875),
    secondary = Color(0xFF6B7280),
    tertiary = Color(0xFF34C759),
    background = Color(0xFFF4F5F8),
    onBackground = Color(0xFF13151A),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECEFF4),
    outline = Color(0xFFD6DBE4),
    onSurface = Color(0xFF13151A),
    onSurfaceVariant = Color(0xFF5A6270),
    error = Color(0xFFFF3B30),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val AuroraColorScheme = darkColorScheme(
    primary = AuroraPrimary,
    onPrimary = AuroraOnPrimary,
    secondary = AuroraSecondary,
    tertiary = SignalBlue,
    background = AuroraDeepBg,
    onBackground = AuroraText,
    surface = AuroraSurface,
    surfaceVariant = AuroraSurfaceAlt,
    outline = AuroraOutline,
    onSurface = AuroraText,
    onSurfaceVariant = AuroraMuted,
    primaryContainer = AuroraPrimaryContainer,
    onPrimaryContainer = AuroraOnPrimaryContainer,
    secondaryContainer = Color(0xFF162C35),
    onSecondaryContainer = Color(0xFFBAE6FD),
    error = Color(0xFFFF5252),
    errorContainer = Color(0xFF3B1518),
    onErrorContainer = Color(0xFFFFB4AB),
)

private val SunsetColorScheme = darkColorScheme(
    primary = SunsetPrimary,
    onPrimary = SunsetOnPrimary,
    secondary = SunsetSecondary,
    tertiary = SignalMint,
    background = SunsetDeepBg,
    onBackground = SunsetText,
    surface = SunsetSurface,
    surfaceVariant = SunsetSurfaceAlt,
    outline = SunsetOutline,
    onSurface = SunsetText,
    onSurfaceVariant = SunsetMuted,
    primaryContainer = SunsetPrimaryContainer,
    onPrimaryContainer = SunsetOnPrimaryContainer,
    secondaryContainer = Color(0xFF35232C),
    onSecondaryContainer = Color(0xFFFCE7F3),
    error = Color(0xFFFF5252),
    errorContainer = Color(0xFF3E161A),
    onErrorContainer = Color(0xFFFFB4AB),
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
    themeName: String = if (darkTheme) "Dark" else "Light",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val isLight = themeName == "Light" && !darkTheme
    val colorScheme = when {
        themeName == "Aurora" -> AuroraColorScheme
        themeName == "Sunset" -> SunsetColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme || themeName == "Dark" -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = isLight
            insetsController.isAppearanceLightNavigationBars = isLight
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content,
    )
}

