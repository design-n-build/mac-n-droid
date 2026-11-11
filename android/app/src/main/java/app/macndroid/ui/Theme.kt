package app.macndroid.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

private val LightFallback = lightColorScheme(
    primary = Color(0xFF2F5FD0),
    secondary = Color(0xFF1C7C5A),
)
private val DarkFallback = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    secondary = Color(0xFF8BD6B4),
)

/**
 * Material 3 with Material You dynamic color (Android 12+), following the phone's theme and light/dark
 * setting. No fixed colors: everything is a role of the system color scheme.
 */
@Composable
fun MacNDroidTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val base = previewColors?.invoke(dark) ?: when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    // Same roles as Google's settings screens (Gboard, Settings): the page sits on surfaceContainer and
    // rows on surfaceBright. Only roles are remapped, so every color still comes from the phone's theme.
    val scheme = base.copy(background = base.surfaceContainer, surface = base.surfaceContainer)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Lets the store-screenshot test (src/test) choose a wallpaper palette. Always null in the app. */
@androidx.annotation.VisibleForTesting
internal var previewColors: ((dark: Boolean) -> androidx.compose.material3.ColorScheme)? = null

/** Settings rows and list cards, like Gboard's: a step brighter than the page in both light and dark. */
val tileColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceBright

/** Rows inside a [SectionCard]: nearly square inner corners; the group itself rounds the outer ones. */
fun Modifier.tileRow() = this.clip(RoundedCornerShape(4.dp))
