package net.qmindtech.tmap.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/**
 * Material3 bridge scheme. Midnight Calm is the source of truth; this exists so M3 components
 * (ripple, default text colors, selection handles) render coherently. App UI reads TmapColors
 * via LocalTmapColors, never these roles directly.
 */
val TmapDarkColorScheme: ColorScheme = darkColorScheme(
    primary = MidnightCalmColors.accent,
    onPrimary = MidnightCalmColors.onAccent,
    secondary = MidnightCalmColors.accentEnd,
    onSecondary = MidnightCalmColors.onAccent,
    background = MidnightCalmColors.bgBottom,
    onBackground = MidnightCalmColors.textPrimary,
    surface = MidnightCalmColors.surface,
    onSurface = MidnightCalmColors.textPrimary,
    surfaceVariant = MidnightCalmColors.surfaceRaised,
    onSurfaceVariant = MidnightCalmColors.textSecondary,
    surfaceContainer = MidnightCalmColors.surfaceRaised,
    surfaceContainerHigh = MidnightCalmColors.surfaceRaised,
    outline = MidnightCalmColors.borderStrong,
    outlineVariant = MidnightCalmColors.borderSubtle,
    tertiary = MidnightCalmColors.success,
    onTertiary = MidnightCalmColors.onAccent,
    error = MidnightCalmColors.danger,
    onError = MidnightCalmColors.onAccent,
)

@Composable
fun TmapTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalTmapColors provides MidnightCalmColors,
        LocalTmapShapes provides TmapDefaultShapes,
        LocalTmapSpacing provides TmapDefaultSpacing,
        LocalTmapType provides TmapDefaultType,
        LocalTmapMotion provides TmapDefaultMotion,
        LocalReduceMotion provides rememberReduceMotion(),
    ) {
        MaterialTheme(
            colorScheme = TmapDarkColorScheme,
            typography = TmapMaterialTypography,
            content = content,
        )
    }
}

/** The app-wide vertical background gradient (bgTop → bgBottom). Every screen sits on this. */
@Composable
fun TmapBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalTmapColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bgTop, colors.bgBottom))),
        content = content,
    )
}
