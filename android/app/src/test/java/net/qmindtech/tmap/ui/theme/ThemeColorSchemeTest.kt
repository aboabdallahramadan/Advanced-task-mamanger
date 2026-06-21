package net.qmindtech.tmap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorSchemeTest {
    @Test
    fun materialBridgeMapsMidnightCalmTokens() {
        val c = MidnightCalmColors
        // Background and surface roles bridge to the warm dark tokens (not the old desktop palette).
        assertEquals(c.bgBottom, TmapDarkColorScheme.background)
        assertEquals(c.surface, TmapDarkColorScheme.surface)
        assertEquals(c.textPrimary, TmapDarkColorScheme.onSurface)
        // The single accent is the M3 primary; error bridges to the soft danger.
        assertEquals(c.accent, TmapDarkColorScheme.primary)
        assertEquals(c.onAccent, TmapDarkColorScheme.onPrimary)
        assertEquals(c.danger, TmapDarkColorScheme.error)
    }
}
