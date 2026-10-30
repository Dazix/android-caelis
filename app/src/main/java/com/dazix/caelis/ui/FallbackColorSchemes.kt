package com.dazix.caelis.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The schemes Android 11 draws the app in. Dynamic colour exists from Android 12, so below it there is
 * no wallpaper palette to follow and [CaelisTheme] falls back to these: a warm amber tonal palette, the
 * one [com.dazix.caelis.overlay.OverlayPalette] already paints the floating panel in on any phone older
 * than Android 14. The dark scheme's surface, outline, primary, secondary container and error roles are
 * that panel's colours exactly, so the app and the panel over the camera agree on such a phone.
 */
internal val expressiveDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB870),
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDCBE),
    secondary = Color(0xFFE1C1A4),
    onSecondary = Color(0xFF402C18),
    secondaryContainer = Color(0xFF26456F),
    onSecondaryContainer = Color(0xFFD7E2FF),
    tertiary = Color(0xFFABC7FF),
    onTertiary = Color(0xFF0D2F5F),
    tertiaryContainer = Color(0xFF26456F),
    onTertiaryContainer = Color(0xFFD7E2FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF73342C),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF19120C),
    onBackground = Color(0xFFEDE0D4),
    surface = Color(0xFF19120C),
    onSurface = Color(0xFFEDE0D4),
    surfaceVariant = Color(0xFF504539),
    onSurfaceVariant = Color(0xFFD5C3B5),
    outline = Color(0xFF9D8E81),
    outlineVariant = Color(0xFF504539),
    surfaceContainerLowest = Color(0xFF130D07),
    surfaceContainerLow = Color(0xFF211A14),
    surfaceContainer = Color(0xFF251E17),
    surfaceContainerHigh = Color(0xFF302821),
    surfaceContainerHighest = Color(0xFF3B332B),
)

internal val expressiveLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF845416),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCBE),
    onPrimaryContainer = Color(0xFF693C00),
    secondary = Color(0xFF715A41),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E2FF),
    onSecondaryContainer = Color(0xFF26456F),
    tertiary = Color(0xFF3E5F90),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD7E2FF),
    onTertiaryContainer = Color(0xFF26456F),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF211A14),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF211A14),
    surfaceVariant = Color(0xFFF2DFD1),
    onSurfaceVariant = Color(0xFF504539),
    outline = Color(0xFF827568),
    outlineVariant = Color(0xFFD5C3B5),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1E7),
    surfaceContainer = Color(0xFFFAEBE0),
    surfaceContainerHigh = Color(0xFFF4E6DA),
    surfaceContainerHighest = Color(0xFFEEE0D5),
)
