package com.vellum.notes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Brand typefaces: Fraunces 500/600 for display + titles, Inter 400/500/600
// for body/UI. Offline-safe mapping below uses the closest built-in
// serif/sans pairing. Switching to the real Fraunces/Inter font files later
// is a one-line change: point these two vals at the loaded FontFamily.
val VellumDisplayFontFamily: FontFamily = FontFamily.Serif
val VellumBodyFontFamily: FontFamily = FontFamily.Default

private val base = Typography()

val Typography = Typography(
    displayLarge = base.displayLarge.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.Medium
    ),
    displayMedium = base.displayMedium.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.Medium
    ),
    displaySmall = base.displaySmall.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.Medium
    ),
    headlineLarge = base.headlineLarge.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.Medium
    ),
    headlineMedium = base.headlineMedium.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.Medium
    ),
    headlineSmall = base.headlineSmall.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.Medium
    ),
    titleLarge = base.titleLarge.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.SemiBold
    ),
    titleMedium = base.titleMedium.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.SemiBold
    ),
    titleSmall = base.titleSmall.copy(
        fontFamily = VellumDisplayFontFamily, fontWeight = FontWeight.Medium
    ),
    bodyLarge = base.bodyLarge.copy(fontFamily = VellumBodyFontFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = VellumBodyFontFamily),
    bodySmall = base.bodySmall.copy(fontFamily = VellumBodyFontFamily),
    labelLarge = base.labelLarge.copy(fontFamily = VellumBodyFontFamily),
    labelMedium = base.labelMedium.copy(fontFamily = VellumBodyFontFamily),
    labelSmall = base.labelSmall.copy(fontFamily = VellumBodyFontFamily),
)
