package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorTest {
    @Test
    fun surfacePaletteMatchesDesktopHexes() {
        assertEquals(Color(0xFF020617), Surface950)
        assertEquals(Color(0xFF0F172A), Surface900)
        assertEquals(Color(0xFFF8FAFC), Surface50)
    }

    @Test
    fun semanticPaletteMatchesDesktopHexes() {
        assertEquals(Color(0xFF3B82F6), Accent500)
        assertEquals(Color(0xFF22C55E), Success500)
        assertEquals(Color(0xFFF59E0B), Warning500)
        assertEquals(Color(0xFFEF4444), Danger500)
    }
}
