package net.qmindtech.tmap.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Dark-only. No dynamic color — the brand palette is fixed across all devices.
val TmapDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Accent500,
    onPrimary = Surface50,
    primaryContainer = Accent700,
    onPrimaryContainer = Accent100,
    secondary = Accent400,
    onSecondary = Surface950,
    background = Surface950,
    onBackground = Surface200,
    surface = Surface900,
    onSurface = Surface200,
    surfaceVariant = Surface800,
    onSurfaceVariant = Surface400,
    surfaceContainer = Surface800,
    surfaceContainerHigh = Surface700,
    outline = Surface700,
    outlineVariant = Surface800,
    error = Danger500,
    onError = Surface50,
    errorContainer = Danger600,
    onErrorContainer = Danger300,
    tertiary = Success500,
    onTertiary = Surface950,
)

@Composable
fun TmapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TmapDarkColorScheme,
        typography = TmapTypography,
        content = content,
    )
}
