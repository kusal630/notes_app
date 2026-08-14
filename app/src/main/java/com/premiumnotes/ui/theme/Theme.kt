package com.premiumnotes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF2E5BFF),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE1FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF0B1E5E),
    secondary = androidx.compose.ui.graphics.Color(0xFF5B5E71),
    background = androidx.compose.ui.graphics.Color(0xFFFBFBFD),
    surface = androidx.compose.ui.graphics.Color(0xFFFBFBFD),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFF2F0F4),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFAAC4FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF0B1E5E),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF3F5BA8),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFDCE1FF),
    secondary = androidx.compose.ui.graphics.Color(0xFFC4C6D8),
    background = androidx.compose.ui.graphics.Color(0xFF15161A),
    surface = androidx.compose.ui.graphics.Color(0xFF15161A),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF23252B),
)

@Composable
fun PremiumNotesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
