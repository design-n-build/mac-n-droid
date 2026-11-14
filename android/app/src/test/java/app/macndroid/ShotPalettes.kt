package app.macndroid

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Wallpaper palettes for the rendered screens (StoreShots and VideoShots). The app itself always takes
// its colours from the phone (Material You); these stand in for a phone's wallpaper.

// Material You from a green wallpaper (seed #0DA457, the brand green), tonal-spot roles.
internal val greenLight = lightColorScheme(
    primary = Color(0xFF006D3D), onPrimary = Color.White, primaryContainer = Color(0xFF98F7B5), onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4F6354), onSecondary = Color.White, secondaryContainer = Color(0xFFD1E8D5), onSecondaryContainer = Color(0xFF0C1F13),
    tertiary = Color(0xFF3B6470), tertiaryContainer = Color(0xFFBEEAF7), onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFF6FBF4), onBackground = Color(0xFF171D19), surface = Color(0xFFF6FBF4), onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DC), onSurfaceVariant = Color(0xFF414942), outline = Color(0xFF717971), outlineVariant = Color(0xFFC0C9C0),
    surfaceBright = Color(0xFFF6FBF4), surfaceDim = Color(0xFFD6DBD5), surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF0F5EE), surfaceContainer = Color(0xFFEAEFE8), surfaceContainerHigh = Color(0xFFE5EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD), inverseSurface = Color(0xFF2C322D), inverseOnSurface = Color(0xFFEDF2EB),
)
internal val greenDark = darkColorScheme(
    primary = Color(0xFF7CDA9B), onPrimary = Color(0xFF00391D), primaryContainer = Color(0xFF00522C), onPrimaryContainer = Color(0xFF98F7B5),
    secondary = Color(0xFFB5CCBA), onSecondary = Color(0xFF213527), secondaryContainer = Color(0xFF374B3D), onSecondaryContainer = Color(0xFFD1E8D5),
    tertiary = Color(0xFFA3CDDB), tertiaryContainer = Color(0xFF214C57), onTertiaryContainer = Color(0xFFBEEAF7),
    background = Color(0xFF0F1511), onBackground = Color(0xFFDFE4DD), surface = Color(0xFF0F1511), onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF414942), onSurfaceVariant = Color(0xFFC0C9C0), outline = Color(0xFF8A938B), outlineVariant = Color(0xFF414942),
    surfaceBright = Color(0xFF353B36), surfaceDim = Color(0xFF0F1511), surfaceContainerLowest = Color(0xFF0A0F0C),
    surfaceContainerLow = Color(0xFF171D19), surfaceContainer = Color(0xFF1B211D), surfaceContainerHigh = Color(0xFF252B27),
    surfaceContainerHighest = Color(0xFF303632), inverseSurface = Color(0xFFDFE4DD), inverseOnSurface = Color(0xFF2C322D),
)

// Material You "Monochrome" style (Android 14+ Wallpaper & style): neutral greys, black/white accents.
// Used for the website, which is black, grey and white:  ./gradlew storeShots -Ppalette=mono -PshotsOut=<folder>
private fun g(t: Int) = Color(0xFF000000 or (t.toLong() shl 16) or (t.toLong() shl 8) or t.toLong())
internal val monoLight = lightColorScheme(
    primary = g(0x00), onPrimary = g(0xE2), primaryContainer = g(0x3B), onPrimaryContainer = g(0xFF),
    secondary = g(0x39), onSecondary = g(0xE2), secondaryContainer = g(0xD4), onSecondaryContainer = g(0x1B),
    tertiary = g(0x3B), onTertiary = g(0xE2), tertiaryContainer = g(0x77), onTertiaryContainer = g(0xFF),
    background = g(0xF9), onBackground = g(0x1B), surface = g(0xF9), onSurface = g(0x1B),
    surfaceVariant = g(0xE2), onSurfaceVariant = g(0x47), outline = g(0x77), outlineVariant = g(0xC6),
    surfaceBright = g(0xF9), surfaceDim = g(0xDA), surfaceContainerLowest = g(0xFF),
    surfaceContainerLow = g(0xF3), surfaceContainer = g(0xEE), surfaceContainerHigh = g(0xE8),
    surfaceContainerHighest = g(0xE2), inverseSurface = g(0x30), inverseOnSurface = g(0xF1),
)
internal val monoDark = darkColorScheme(
    primary = g(0xFF), onPrimary = g(0x1B), primaryContainer = g(0xD4), onPrimaryContainer = g(0x00),
    secondary = g(0xC6), onSecondary = g(0x30), secondaryContainer = g(0x47), onSecondaryContainer = g(0xE2),
    tertiary = g(0xE2), onTertiary = g(0x1B), tertiaryContainer = g(0x91), onTertiaryContainer = g(0x00),
    background = g(0x13), onBackground = g(0xE2), surface = g(0x13), onSurface = g(0xE2),
    surfaceVariant = g(0x47), onSurfaceVariant = g(0xC6), outline = g(0x91), outlineVariant = g(0x47),
    surfaceBright = g(0x39), surfaceDim = g(0x13), surfaceContainerLowest = g(0x0E),
    surfaceContainerLow = g(0x1B), surfaceContainer = g(0x1F), surfaceContainerHigh = g(0x2A),
    surfaceContainerHighest = g(0x35), inverseSurface = g(0xE2), inverseOnSurface = g(0x30),
)
