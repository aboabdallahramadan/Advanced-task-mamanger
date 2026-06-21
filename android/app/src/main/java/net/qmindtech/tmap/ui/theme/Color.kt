package net.qmindtech.tmap.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Midnight Calm color tokens (spec §4.1). Dark-only; never the old desktop Surface/Accent palette.
 * Field names are the FIXED cross-phase contract (`colors.accent`, `colors.surface`, etc.).
 */
@Immutable
data class TmapColorScheme(
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceInset: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textBody: Color,
    val accent: Color,
    val accentEnd: Color,
    val onAccent: Color,
    val success: Color,
    val successStart: Color,
    val danger: Color,
    val focusBgTop: Color,
    val focusBgBottom: Color,
    // Project default/legend colors (projects store their own; these are the defaults).
    val projectWork: Color,
    val projectPersonal: Color,
    val projectHealth: Color,
    val projectIdeas: Color,
    val projectLearning: Color,
)

val MidnightCalmColors: TmapColorScheme = TmapColorScheme(
    bgTop = Color(0xFF191A20),
    bgBottom = Color(0xFF141519),
    surface = Color(0xFF202127),
    surfaceRaised = Color(0xFF23242B),
    surfaceInset = Color(0xFF1C1D23),
    borderSubtle = Color(0xFF2A2B31),
    borderStrong = Color(0xFF34353C),
    textPrimary = Color(0xFFECEAE4),
    textSecondary = Color(0xFF908E86),
    textTertiary = Color(0xFF76746D),
    textBody = Color(0xFFB7B5AD),
    accent = Color(0xFFE8A87C),
    accentEnd = Color(0xFFE0936A),
    onAccent = Color(0xFF1A1208),
    success = Color(0xFF38D39F),
    successStart = Color(0xFF2F7D5B),
    danger = Color(0xFFF0A0A0),
    focusBgTop = Color(0xFF1B1C22),
    focusBgBottom = Color(0xFF121317),
    projectWork = Color(0xFF6EA8FE),
    projectPersonal = Color(0xFF38D39F),
    projectHealth = Color(0xFFF0A868),
    projectIdeas = Color(0xFFC9A0FF),
    projectLearning = Color(0xFFF0A0A0),
)

val LocalTmapColors = staticCompositionLocalOf { MidnightCalmColors }
