package net.qmindtech.tmap.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable

// Dark-only. No dynamic color — the brand palette is fixed across all devices.
// internal (not private) so ThemeColorSchemeTest can verify token→slot mappings.
internal val TmapDarkColorScheme: ColorScheme = darkColorScheme(
    primary = MidnightCalmColors.accent,
    onPrimary = MidnightCalmColors.onAccent,
    primaryContainer = MidnightCalmColors.accentEnd,
    onPrimaryContainer = MidnightCalmColors.textPrimary,
    secondary = MidnightCalmColors.accent,
    onSecondary = MidnightCalmColors.onAccent,
    background = MidnightCalmColors.bgBottom,
    onBackground = MidnightCalmColors.textBody,
    surface = MidnightCalmColors.surface,
    onSurface = MidnightCalmColors.textPrimary,
    surfaceVariant = MidnightCalmColors.surfaceRaised,
    onSurfaceVariant = MidnightCalmColors.textSecondary,
    surfaceContainer = MidnightCalmColors.surfaceRaised,
    surfaceContainerHigh = MidnightCalmColors.surfaceInset,
    outline = MidnightCalmColors.borderStrong,
    outlineVariant = MidnightCalmColors.borderSubtle,
    error = MidnightCalmColors.danger,
    onError = MidnightCalmColors.textPrimary,
    errorContainer = MidnightCalmColors.danger,
    onErrorContainer = MidnightCalmColors.textPrimary,
    tertiary = MidnightCalmColors.success,
    onTertiary = MidnightCalmColors.bgBottom,
)

@Composable
fun TmapTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTmapColors provides MidnightCalmColors,
        LocalTmapType provides TmapDefaultType,
    ) {
        MaterialTheme(
            colorScheme = TmapDarkColorScheme,
            typography = TmapMaterialTypography,
            content = content,
        )
    }
}
