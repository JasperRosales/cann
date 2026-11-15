package app.cann.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** isha's palette: warm cream by day, espresso by night, orange accent. */
@Immutable
data class Palette(
    val bg: Color, val bgDeep: Color, val surfaceHi: Color, val surfaceLo: Color,
    val ink: Color, val muted: Color,
    val accent: Color, val accentDeep: Color, val accentSoft: Color, val onAccent: Color,
    val success: Color, val warn: Color, val danger: Color,
    val codeBg: Color, val stroke: Color, val grid: Color,
)

val LightPalette = Palette(
    bg = Color(0xFFFAF3E1), bgDeep = Color(0xFFF3E4C4),
    surfaceHi = Color(0xFFFFFDF6), surfaceLo = Color(0xFFF8EBCF),
    ink = Color(0xFF241F19), muted = Color(0xFF7C6F5D),
    accent = Color(0xFFFF6D1F), accentDeep = Color(0xFFC2410C), accentSoft = Color(0xFFFFE0CC),
    onAccent = Color(0xFFFFF6E8),
    success = Color(0xFF2E7D5B), warn = Color(0xFFB7791F), danger = Color(0xFFB3261E),
    codeBg = Color(0xFFF0E1C2), stroke = Color(0x336B5A3E), grid = Color(0x266B5A3E),
)

val DarkPalette = Palette(
    bg = Color(0xFF17130F), bgDeep = Color(0xFF0F0C08),
    surfaceHi = Color(0xFF2C251D), surfaceLo = Color(0xFF211B15),
    ink = Color(0xFFF7EEDD), muted = Color(0xFFA9987F),
    accent = Color(0xFFFF8A3D), accentDeep = Color(0xFFDD5711), accentSoft = Color(0xFF3A2214),
    onAccent = Color(0xFF1B1108),
    success = Color(0xFF63C79B), warn = Color(0xFFE8B25A), danger = Color(0xFFFF6F61),
    codeBg = Color(0xFF221B14), stroke = Color(0x33FFF0D0), grid = Color(0x22FFF0D0),
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val P: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current

val Mono = FontFamily.Monospace

@Composable
fun CannTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = p.accent, onPrimary = p.onAccent,
        primaryContainer = p.accentSoft, onPrimaryContainer = p.ink,
        secondary = p.accentDeep, onSecondary = p.onAccent,
        secondaryContainer = p.accentSoft, onSecondaryContainer = p.accent,
        tertiary = p.success,
        background = p.bg, onBackground = p.ink, surface = p.bg, onSurface = p.ink,
        surfaceVariant = p.bgDeep, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.bgDeep, surfaceContainerLow = p.bg,
        surfaceContainer = p.surfaceLo, surfaceContainerHigh = p.surfaceHi, surfaceContainerHighest = p.surfaceHi,
        inverseSurface = p.ink, inverseOnSurface = p.bg,
        outline = p.stroke, outlineVariant = p.stroke,
        error = p.danger,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
                large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
