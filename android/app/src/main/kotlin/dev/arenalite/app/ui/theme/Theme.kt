package dev.arenalite.app.ui.theme

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

val Accent = Color(0xFF7C8CFF)
val AccentCyan = Color(0xFF4DD0E1)
val SurfaceDark = Color(0xFF11141C)
val SurfaceDarker = Color(0xFF0B0D12)
val LineDark = Color(0xFF232838)
val TextDim = Color(0xFF98A1B3)
val OkGreen = Color(0xFF4ADE80)

private val DarkColors = darkColorScheme(
    primary = Accent,
    secondary = AccentCyan,
    background = SurfaceDarker,
    surface = SurfaceDark,
    surfaceVariant = Color(0xFF171B25),
    onPrimary = Color(0xFF0B0D12),
    onBackground = Color(0xFFE7EAF0),
    onSurface = Color(0xFFE7EAF0),
    outline = LineDark,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4A5AE8),
    secondary = Color(0xFF0097A7),
    background = Color(0xFFF7F8FB),
    surface = Color(0xFFFFFFFF),
    outline = Color(0xFFDDE1EA),
)

val ArealiteTypography = Typography(
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, color = TextDim),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
)

@Composable
fun ArealiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = ArealiteTypography,
        content = content,
    )
}
