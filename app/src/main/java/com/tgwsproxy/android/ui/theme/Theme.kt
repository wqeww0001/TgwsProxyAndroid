package com.tgwsproxy.android.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Cyan80,
    secondary = Teal80,
    tertiary = Amber80,
    background = DeepNight,
    surface = NightPanel,
    surfaceVariant = NightPanelAlt,
    outline = NightLine,
    onSurface = androidx.compose.ui.graphics.Color(0xFFEAF6FA),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9EB4C1),
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
        content = content
    )
}
