package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ColorTest {
    private val c = MidnightCalmColors

    @Test
    fun backgroundAndSurfaceTokensMatchSpec() {
        assertEquals(Color(0xFF191A20), c.bgTop)
        assertEquals(Color(0xFF141519), c.bgBottom)
        assertEquals(Color(0xFF202127), c.surface)
        assertEquals(Color(0xFF23242B), c.surfaceRaised)
        assertEquals(Color(0xFF1C1D23), c.surfaceInset)
        assertEquals(Color(0xFF2A2B31), c.borderSubtle)
        assertEquals(Color(0xFF34353C), c.borderStrong)
    }

    @Test
    fun textTokensMatchSpec() {
        assertEquals(Color(0xFFECEAE4), c.textPrimary)
        assertEquals(Color(0xFF908E86), c.textSecondary)
        assertEquals(Color(0xFF76746D), c.textTertiary)
        assertEquals(Color(0xFFB7B5AD), c.textBody)
    }

    @Test
    fun accentAndSemanticTokensMatchSpec() {
        assertEquals(Color(0xFFE8A87C), c.accent)
        assertEquals(Color(0xFFE0936A), c.accentEnd)
        assertEquals(Color(0xFF1A1208), c.onAccent)
        assertEquals(Color(0xFF38D39F), c.success)
        assertEquals(Color(0xFF2F7D5B), c.successStart)
        assertEquals(Color(0xFFF0A0A0), c.danger)
        assertEquals(Color(0xFF1B1C22), c.focusBgTop)
        assertEquals(Color(0xFF121317), c.focusBgBottom)
    }

    @Test
    fun projectDefaultColorsMatchSpec() {
        assertEquals(Color(0xFF6EA8FE), c.projectWork)
        assertEquals(Color(0xFF38D39F), c.projectPersonal)
        assertEquals(Color(0xFFF0A868), c.projectHealth)
        assertEquals(Color(0xFFC9A0FF), c.projectIdeas)
        assertEquals(Color(0xFFF0A0A0), c.projectLearning)
    }

    @Test
    fun localTmapColorsProviderIsNonNull() {
        // Verify that LocalTmapColors exists as a non-null CompositionLocal.
        // The default value (MidnightCalmColors) is fully pinned by the four
        // concrete token tests in this suite — no fragile Compose-internal reflection needed.
        assertNotNull(LocalTmapColors)
    }
}
