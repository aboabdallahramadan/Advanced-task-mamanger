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
    fun localTmapColorsDefaultsToMidnightCalm() {
        // Verify LocalTmapColors is wired to MidnightCalmColors by inspecting the
        // LazyValueHolder inside CompositionLocal.defaultValueHolder via reflection.
        // staticCompositionLocalOf { MidnightCalmColors } stores a LazyValueHolder whose
        // internal Lazy delegate evaluates to MidnightCalmColors.
        assertNotNull(LocalTmapColors)
        val holderField = LocalTmapColors::class.java.superclass?.superclass
            ?.getDeclaredField("defaultValueHolder")
            ?: error("CompositionLocal.defaultValueHolder field not found")
        holderField.isAccessible = true
        val holder = holderField.get(LocalTmapColors)
            ?: error("defaultValueHolder is null")

        // LazyValueHolder stores the default in "current$delegate" (a kotlin.Lazy<T>).
        val delegateField = holder.javaClass.getDeclaredField("current\$delegate")
        delegateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val lazy = delegateField.get(holder) as Lazy<TmapColorScheme>
        assertEquals(MidnightCalmColors, lazy.value)
    }
}
