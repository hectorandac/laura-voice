package dev.hect.lauravoice

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal val Bg = Color(0xFF0F0D17)
internal val Accent = Color(0xFF8B7CF6)
internal val SurfaceTone = Color(0xFF1A1724)
internal val TextPrimary = Color(0xFFECEAF5)
internal val TextSecondary = Color(0xFFB9B4CC)
internal val TextMuted = Color(0xFF8E89A3)
internal val Warn = Color(0xFFFACC15)
internal val Bad = Color(0xFFF87171)
internal val Good = Color(0xFF4ADE80)

@Composable
fun LauraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, background = Bg, surface = Bg, surfaceContainer = SurfaceTone,
            surfaceContainerHigh = SurfaceTone, onBackground = TextPrimary, onSurface = TextPrimary,
            onSurfaceVariant = TextSecondary,
        ),
        content = content,
    )
}
