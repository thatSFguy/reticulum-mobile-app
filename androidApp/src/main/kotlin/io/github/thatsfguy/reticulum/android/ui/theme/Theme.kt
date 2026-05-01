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
 */

private val LightAccent      = Color(0xFF1D9E75)
private val LightAccentBg    = Color(0xFFE1F5EE)
private val LightAccentText  = Color(0xFF0F6E56)
private val LightBackground  = Color(0xFFEEECE6)
private val LightSurface     = Color(0xFFF5F4F0)
private val LightSurface2    = Color(0xFFFFFFFF)
private val LightTextPrimary = Color(0xFF1A1A18)
private val LightTextOnAccent = Color.White
private val LightError       = Color(0xFFA32D2D)

private val DarkAccent       = Color(0xFF5EB0FF)
private val DarkAccentBg     = Color(0xFF1A3A5C)
private val DarkAccentText   = Color(0xFFA8D0FF)
private val DarkBackground   = Color(0xFF0F1115)
private val DarkSurface      = Color(0xFF171A21)
private val DarkSurface2     = Color(0xFF1E2230)
private val DarkTextPrimary  = Color(0xFFE6E8EE)
private val DarkTextOnAccent = Color(0xFF0F1115)
private val DarkError        = Color(0xFFF87171)

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightTextOnAccent,
    primaryContainer = LightAccentBg,
    onPrimaryContainer = LightAccentText,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface2,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurface,
    error = LightError,
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkTextOnAccent,
    primaryContainer = DarkAccentBg,
    onPrimaryContainer = DarkAccentText,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface2,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurface,
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
