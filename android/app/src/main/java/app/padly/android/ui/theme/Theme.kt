package app.padly.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C5CFF)
private val AccentDark = Color(0xFF5B3EDC)
private val Ink900 = Color(0xFF0E0E11)
private val Ink800 = Color(0xFF1F1F24)
private val Ink400 = Color(0xFF8C8C97)
private val Ink100 = Color(0xFFEDEDEF)

private val DarkColors = darkColorScheme(
    primary = Accent,
    secondary = AccentDark,
    background = Ink900,
    surface = Ink800,
    onPrimary = Color.White,
    onBackground = Ink100,
    onSurface = Ink100,
    surfaceVariant = Ink800,
    onSurfaceVariant = Ink400,
)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondary = AccentDark,
)

@Composable
fun PadlyTheme(useDark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        content = content,
    )
}
