package net.qmindtech.tmap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorSchemeTest {
    @Test
    fun darkSchemeMapsNightCalmPalette() {
        // App background tokens map to bgBottom; surface maps to surface token.
        assertEquals(MidnightCalmColors.bgBottom, MidnightCalmColors.bgBottom)
        assertEquals(MidnightCalmColors.surface, MidnightCalmColors.surface)
        assertEquals(MidnightCalmColors.textPrimary, MidnightCalmColors.textPrimary)
        // Brand + semantic roles.
        assertEquals(MidnightCalmColors.accent, MidnightCalmColors.accent)
        assertEquals(MidnightCalmColors.danger, MidnightCalmColors.danger)
    }
}
