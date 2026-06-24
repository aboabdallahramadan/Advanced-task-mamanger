package net.qmindtech.tmap.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider

/**
 * Midnight Calm tokens as Glance ColorProviders (spec §4.1 / §8). Glance can't read Compose
 * CompositionLocals, so widgets reference these directly. Dark-only — same value day or night.
 */
object WidgetColors {
    val bg = ColorProvider(Color(0xFF15161B))          // between bgTop/bgBottom for the rounded card
    val surface = ColorProvider(Color(0xFF1C1D23))     // surfaceInset — the widget body, ~.86 alpha look
    val border = ColorProvider(Color(0xFF2A2B31))      // borderSubtle
    val textPrimary = ColorProvider(Color(0xFFECEAE4))
    val textSecondary = ColorProvider(Color(0xFF908E86))
    val textTertiary = ColorProvider(Color(0xFF76746D))
    val accent = ColorProvider(Color(0xFFE8A87C))
    val accentEnd = ColorProvider(Color(0xFFE0936A))
    val onAccent = ColorProvider(Color(0xFF1A1208))
    val success = ColorProvider(Color(0xFF38D39F))
    val ringTrack = ColorProvider(Color(0xFF2A2B31))   // progress ring unfilled track
}
