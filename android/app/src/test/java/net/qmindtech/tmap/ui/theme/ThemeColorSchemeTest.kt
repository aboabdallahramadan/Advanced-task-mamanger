package net.qmindtech.tmap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeColorSchemeTest {
    @Test
    fun darkSchemeMapsNightCalmPalette() {
        // Verify that Theme.kt wires Midnight Calm tokens into the correct Material3 slots.
        // TmapDarkColorScheme is internal so this test module can read it directly.

        // Background / surface slots
        assertEquals(MidnightCalmColors.bgBottom, TmapDarkColorScheme.background)
        assertEquals(MidnightCalmColors.textBody, TmapDarkColorScheme.onBackground)
        assertEquals(MidnightCalmColors.surface, TmapDarkColorScheme.surface)
        assertEquals(MidnightCalmColors.textPrimary, TmapDarkColorScheme.onSurface)
        assertEquals(MidnightCalmColors.surfaceRaised, TmapDarkColorScheme.surfaceVariant)
        assertEquals(MidnightCalmColors.textSecondary, TmapDarkColorScheme.onSurfaceVariant)
        assertEquals(MidnightCalmColors.surfaceRaised, TmapDarkColorScheme.surfaceContainer)
        assertEquals(MidnightCalmColors.surfaceInset, TmapDarkColorScheme.surfaceContainerHigh)

        // Brand / accent slots
        assertEquals(MidnightCalmColors.accent, TmapDarkColorScheme.primary)
        assertEquals(MidnightCalmColors.onAccent, TmapDarkColorScheme.onPrimary)
        assertEquals(MidnightCalmColors.accentEnd, TmapDarkColorScheme.primaryContainer)
        assertEquals(MidnightCalmColors.textPrimary, TmapDarkColorScheme.onPrimaryContainer)

        // Border slots
        assertEquals(MidnightCalmColors.borderStrong, TmapDarkColorScheme.outline)
        assertEquals(MidnightCalmColors.borderSubtle, TmapDarkColorScheme.outlineVariant)

        // Semantic: error, tertiary/success
        assertEquals(MidnightCalmColors.danger, TmapDarkColorScheme.error)
        assertEquals(MidnightCalmColors.textPrimary, TmapDarkColorScheme.onError)
        assertEquals(MidnightCalmColors.success, TmapDarkColorScheme.tertiary)
        assertEquals(MidnightCalmColors.bgBottom, TmapDarkColorScheme.onTertiary)
    }
}
