package net.qmindtech.tmap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorSchemeTest {
    @Test
    fun darkSchemeMapsDesktopPalette() {
        // App background is the deepest surface; cards/surfaces sit one step up.
        assertEquals(Surface950, TmapDarkColorScheme.background)
        assertEquals(Surface900, TmapDarkColorScheme.surface)
        assertEquals(Surface200, TmapDarkColorScheme.onSurface)
        // Brand + semantic roles.
        assertEquals(Accent500, TmapDarkColorScheme.primary)
        assertEquals(Danger500, TmapDarkColorScheme.error)
    }
}
