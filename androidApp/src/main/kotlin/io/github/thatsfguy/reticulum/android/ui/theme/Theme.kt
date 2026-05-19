package io.github.thatsfguy.reticulum.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Palette ported from the webclient's css/style.css. Light theme is the
 * "warm beige + teal" look, dark theme keeps the original blue-on-charcoal.
 *
 * Both schemes set the secondary / tertiary / outline / on-surface-variant
 * roles explicitly — left unset they fall back to Material's generic
 * purple baseline, which leaks through most visibly on the bottom
 * navigation bar in light mode.
 */

// ── Light — warm beige + teal ──
private val LightAccent        = Color(0xFF1D9E75)
private val LightAccentBg      = Color(0xFFE1F5EE)
private val LightAccentText    = Color(0xFF0F6E56)
private val LightBackground    = Color(0xFFEEECE6)
private val LightSurface       = Color(0xFFFFFFFF)
private val LightSurfaceVar    = Color(0xFFEDEBE4)
private val LightTextPrimary   = Color(0xFF1A1A18)
private val LightTextSecondary = Color(0xFF55524A)
private val LightTextOnAccent  = Color(0xFFFFFFFF)
private val LightSecondary     = Color(0xFF4F7A6B)
private val LightSecondaryBg   = Color(0xFFD5E8DF)
private val LightSecondaryText = Color(0xFF1F4538)
private val LightTertiary      = Color(0xFF9A6A2E)
private val LightOutline       = Color(0xFF9C988E)
private val LightOutlineVar    = Color(0xFFD0CDC4)
private val LightError         = Color(0xFFA32D2D)

// ── Dark — blue on charcoal ──
private val DarkAccent         = Color(0xFF5EB0FF)
private val DarkAccentBg       = Color(0xFF1A3A5C)
private val DarkAccentText     = Color(0xFFA8D0FF)
private val DarkBackground     = Color(0xFF0F1115)
private val DarkSurface        = Color(0xFF1E2230)
private val DarkSurfaceVar     = Color(0xFF171A21)
private val DarkTextPrimary    = Color(0xFFE6E8EE)
private val DarkTextSecondary  = Color(0xFFA6ADBC)
private val DarkTextOnAccent   = Color(0xFF0F1115)
private val DarkSecondary      = Color(0xFF8AA6C4)
private val DarkSecondaryBg    = Color(0xFF26313F)
private val DarkSecondaryText  = Color(0xFFCBD9EA)
private val DarkTertiary       = Color(0xFFE0A33A)
private val DarkOutline        = Color(0xFF5C6373)
private val DarkOutlineVar     = Color(0xFF343B49)
private val DarkError          = Color(0xFFF87171)

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightTextOnAccent,
    primaryContainer = LightAccentBg,
    onPrimaryContainer = LightAccentText,
    secondary = LightSecondary,
    onSecondary = LightTextOnAccent,
    secondaryContainer = LightSecondaryBg,
    onSecondaryContainer = LightSecondaryText,
    tertiary = LightTertiary,
    onTertiary = LightTextOnAccent,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVar,
    onSurfaceVariant = LightTextSecondary,
    outline = LightOutline,
    outlineVariant = LightOutlineVar,
    error = LightError,
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkTextOnAccent,
    primaryContainer = DarkAccentBg,
    onPrimaryContainer = DarkAccentText,
    secondary = DarkSecondary,
    onSecondary = DarkTextOnAccent,
    secondaryContainer = DarkSecondaryBg,
    onSecondaryContainer = DarkSecondaryText,
    tertiary = DarkTertiary,
    onTertiary = DarkTextOnAccent,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceVar,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVar,
    error = DarkError,
)

private val ReticulumTypography = Typography(
    bodyLarge   = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium  = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall   = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    titleLarge  = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun ReticulumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = scheme, typography = ReticulumTypography, content = content)
}

/** Monospace for hashes and the diagnostics log. */
val MonoFontFamily: FontFamily = FontFamily.Monospace
