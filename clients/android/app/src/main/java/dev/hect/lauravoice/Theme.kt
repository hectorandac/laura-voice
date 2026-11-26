package dev.hect.lauravoice

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

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

/**
 * Settings palette: a warm near-black (or porcelain) canvas, a surface ladder with hairlines instead of shadows,
 * plum-tinted greys for text and the launcher icon's apricot as the one accent (docs/app/settings-design).
 */
@Immutable
data class Palette(
    val dark: Boolean,
    val canvas: Color, val s1: Color, val s2: Color, val s3: Color, val thumb: Color,
    val hair: Color, val hair2: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val ink4: Color,
    val accent: Color, val onAccent: Color, val accentText: Color, val accentSoft: Color,
    val good: Color, val warn: Color, val bad: Color, val glow: Color,
)

val DarkPalette = Palette(
    dark = true,
    canvas = Color(0xFF0E0A0F), s1 = Color(0xFF171118), s2 = Color(0xFF201922), s3 = Color(0xFF2C2430), thumb = Color(0xFF342B38),
    hair = Color(0x13FFECE4), hair2 = Color(0x21FFECE4),
    ink = Color(0xFFF6EEE9), ink2 = Color(0xFFD2C4CA), ink3 = Color(0xFF9A8A97), ink4 = Color(0xFF6C5E6B),
    accent = Color(0xFFFFB47A), onAccent = Color(0xFF2A1220), accentText = Color(0xFFFFC59A), accentSoft = Color(0x21FFB47A),
    good = Color(0xFF7FD6A0), warn = Color(0xFFF2C063), bad = Color(0xFFF2897F), glow = Color(0x29FFAA6E),
)

val LightPalette = Palette(
    dark = false,
    canvas = Color(0xFFFAF5F1), s1 = Color(0xFFFFFFFF), s2 = Color(0xFFF2EAE4), s3 = Color(0xFFE9DED6), thumb = Color(0xFFFFFFFF),
    hair = Color(0x1646192D), hair2 = Color(0x2646192D),
    ink = Color(0xFF26141E), ink2 = Color(0xFF4F3D48), ink3 = Color(0xFF85717D), ink4 = Color(0xFFB3A2AB),
    accent = Color(0xFFF7A469), onAccent = Color(0xFF2A1220), accentText = Color(0xFF9A3A52), accentSoft = Color(0x2EF7A469),
    good = Color(0xFF2E9A5C), warn = Color(0xFFC2830E), bad = Color(0xFFCC4A40), glow = Color(0x4DFFB06E),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** Follows the system light/dark setting; dialogs, text fields and switches get matching Material colours. */
@Composable
fun SettingsTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = p.onAccent, primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
        secondary = p.accentText, background = p.canvas, onBackground = p.ink, surface = p.canvas, onSurface = p.ink,
        surfaceVariant = p.s2, onSurfaceVariant = p.ink3, surfaceContainerLowest = p.s1, surfaceContainerLow = p.s1,
        surfaceContainer = p.s1, surfaceContainerHigh = p.s1, surfaceContainerHighest = p.s2,
        outline = p.hair2, outlineVariant = p.hair, error = p.bad,
    )
    // Tighter than Material's defaults (0.5 sp tracking, 24 sp lines), closer to the design's Linear-style type.
    val type = Typography().let { t ->
        t.copy(
            bodyLarge = t.bodyLarge.copy(letterSpacing = 0.sp, lineHeight = TextUnit.Unspecified),
            bodyMedium = t.bodyMedium.copy(letterSpacing = 0.sp),
            labelLarge = t.labelLarge.copy(letterSpacing = 0.sp),
        )
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
