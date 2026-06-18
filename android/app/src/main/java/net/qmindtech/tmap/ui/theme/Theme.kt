package net.qmindtech.tmap.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TmapDarkColors = darkColorScheme(
    primary = Accent,
    background = Surface900,
    surface = Surface800,
    onBackground = Surface100,
    onSurface = Surface100,
    error = Danger,
)

@Composable
fun TmapTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TmapDarkColors,
        typography = TmapTypography,
        content = content,
    )
}
