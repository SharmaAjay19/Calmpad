package com.calmpad.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Theme Colors ──

object CalmPadColors {
    // Light
    val LightBackground = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFFAFAF9)
    val LightOnBackground = Color(0xFF292524)
    val LightOnSurface = Color(0xFF44403C)
    val LightMuted = Color(0xFFA8A29E)
    val LightBorder = Color(0xFFE7E5E4)

    // Sepia
    val SepiaBackground = Color(0xFFF4ECD8)
    val SepiaSurface = Color(0xFFEAE0C9)
    val SepiaOnBackground = Color(0xFF433422)
    val SepiaOnSurface = Color(0xFF5C4B37)
    val SepiaMuted = Color(0xFF9C8B74)
    val SepiaBorder = Color(0xFFD3C4A5)

    // Dim
    val DimBackground = Color(0xFF1E293B)
    val DimSurface = Color(0xFF0F172A)
    val DimOnBackground = Color(0xFFCBD5E1)
    val DimOnSurface = Color(0xFF94A3B8)
    val DimMuted = Color(0xFF64748B)
    val DimBorder = Color(0xFF334155)

    // Dark
    val DarkBackground = Color(0xFF000000)
    val DarkSurface = Color(0xFF171717)
    val DarkOnBackground = Color(0xFFA3A3A3)
    val DarkOnSurface = Color(0xFFE5E5E5)
    val DarkMuted = Color(0xFF525252)
    val DarkBorder = Color(0xFF262626)

    // Accent
    val Purple = Color(0xFF7719AA)
    val PurpleLight = Color(0xFFF0EBF8)
}

enum class AppTheme { LIGHT, SEPIA, DIM, DARK }

data class CalmPadColorScheme(
    val background: Color,
    val surface: Color,
    val onBackground: Color,
    val onSurface: Color,
    val muted: Color,
    val border: Color,
    val accent: Color = CalmPadColors.Purple,
    val accentLight: Color = CalmPadColors.PurpleLight,
)

fun appColorScheme(theme: AppTheme): CalmPadColorScheme = when (theme) {
    AppTheme.LIGHT -> CalmPadColorScheme(
        background = CalmPadColors.LightBackground,
        surface = CalmPadColors.LightSurface,
        onBackground = CalmPadColors.LightOnBackground,
        onSurface = CalmPadColors.LightOnSurface,
        muted = CalmPadColors.LightMuted,
        border = CalmPadColors.LightBorder,
    )
    AppTheme.SEPIA -> CalmPadColorScheme(
        background = CalmPadColors.SepiaBackground,
        surface = CalmPadColors.SepiaSurface,
        onBackground = CalmPadColors.SepiaOnBackground,
        onSurface = CalmPadColors.SepiaOnSurface,
        muted = CalmPadColors.SepiaMuted,
        border = CalmPadColors.SepiaBorder,
    )
    AppTheme.DIM -> CalmPadColorScheme(
        background = CalmPadColors.DimBackground,
        surface = CalmPadColors.DimSurface,
        onBackground = CalmPadColors.DimOnBackground,
        onSurface = CalmPadColors.DimOnSurface,
        muted = CalmPadColors.DimMuted,
        border = CalmPadColors.DimBorder,
    )
    AppTheme.DARK -> CalmPadColorScheme(
        background = CalmPadColors.DarkBackground,
        surface = CalmPadColors.DarkSurface,
        onBackground = CalmPadColors.DarkOnBackground,
        onSurface = CalmPadColors.DarkOnSurface,
        muted = CalmPadColors.DarkMuted,
        border = CalmPadColors.DarkBorder,
    )
}

@Composable
fun CalmPadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = CalmPadColors.Purple,
            onPrimary = Color.White,
            surface = CalmPadColors.LightSurface,
            background = CalmPadColors.LightBackground,
        ),
        content = content
    )
}
