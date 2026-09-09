package com.vellum.notes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Brand accent: active tool state and selection (legacy token, kept for compat). */
val VellumAccent = Color(0xFF0E9D8E)

/**
 * Contrast-audited accent variants (>= 4.5:1 for text and non-text UI).
 *
 * - [VellumAccentOnLight] (#0B7A6F): 5.2:1 on white surfaces and 5.2:1 with
 *   white text on top — use for selection strokes/indicators on light theme.
 * - [VellumAccentOnDark] (#3DD5C3): 9.7:1 on dark surface #141821 and 8.3:1
 *   with #062B28 text on top — use for indicators on dark theme.
 * The legacy [VellumAccent] (#0E9D8E) is 3.4:1 on white (fails body text) and
 * is therefore no longer used for text-bearing surfaces.
 */
val VellumAccentOnLight = Color(0xFF0B7A6F)
val VellumAccentOnDark = Color(0xFF3DD5C3)

// Contrast audit (light): onSurface 16.7:1, onSurfaceVariant 6.5:1,
// onPrimaryContainer 10.5:1, onSecondaryContainer 9.9:1, error 6.5:1,
// tertiary/onTertiary (white on #0B7A6F) 5.2:1 — all >= 4.5:1.
private val LightColors = lightColorScheme(
    primary = Color(0xFF232F49),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE4EF),
    onPrimaryContainer = Color(0xFF232F49),
    secondary = Color(0xFF4B5F5A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD4DDD9),
    onSecondaryContainer = Color(0xFF23302C),
    tertiary = VellumAccentOnLight,
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F6F1),
    onBackground = Color(0xFF1B1E24),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1E24),
    surfaceContainer = Color(0xFFF2EFE9),
    surfaceVariant = Color(0xFFE4E1D8),
    onSurfaceVariant = Color(0xFF4A4D55),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF5F6368),
    outlineVariant = Color(0xFF8A887E),
)

// Contrast audit (dark): onSurface 14.2:1, onSurfaceVariant 7.6:1,
// onPrimaryContainer 7.8:1, onSecondaryContainer 6.7:1, error 11.1:1,
// primary/onPrimary 8.0:1, tertiary/onTertiary (#062B28 on #3DD5C3) 8.3:1 —
// all >= 4.5:1.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C0E4),
    onPrimary = Color(0xFF1B2740),
    primaryContainer = Color(0xFF32435F),
    onPrimaryContainer = Color(0xFFDEE4EF),
    secondary = Color(0xFF8FA7A2),
    onSecondary = Color(0xFF1B2740),
    secondaryContainer = Color(0xFF3A4A46),
    onSecondaryContainer = Color(0xFFD4DDD9),
    tertiary = VellumAccentOnDark,
    onTertiary = Color(0xFF062B28),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF141821),
    onSurface = Color(0xFFE8E6E1),
    surfaceContainer = Color(0xFF1B212B),
    surfaceVariant = Color(0xFF2A323E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF1B2740),
    outline = Color(0xFF9AA0AA),
    outlineVariant = Color(0xFF6B7484),
)

@Composable
fun VellumTheme(
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
