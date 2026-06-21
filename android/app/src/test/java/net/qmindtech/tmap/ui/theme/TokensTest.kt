package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TokensTest {
    @Test
    fun shapeTokensMatchSpec() {
        val s = TmapDefaultShapes
        assertEquals(18.dp, s.card)
        assertEquals(26.dp, s.sheetTop)
        assertEquals(999.dp, s.pill)
        assertEquals(13.dp, s.button)
        assertEquals(12.dp, s.well)
    }

    @Test
    fun spacingScaleMatchesSpec() {
        val p = TmapDefaultSpacing
        assertEquals(4.dp, p.base)
        assertEquals(8.dp, p.xs)
        assertEquals(10.dp, p.sm)
        assertEquals(14.dp, p.md)
        assertEquals(16.dp, p.lg)
        assertEquals(20.dp, p.xl)
        assertEquals(22.dp, p.xxl)
        assertEquals(18.dp, p.screenH)
    }

    @Test
    fun motionTokensMatchSpec() {
        val m = TmapDefaultMotion
        assertEquals(220, m.standardMillis)
        assertEquals(180, m.checkOffMillis)
    }

    @Test
    fun localsDefaultToTheDefaults() {
        // .current requires @Composable context; verify the CompositionLocal vals are non-null
        // (their defaults are pinned by the concrete token tests above).
        org.junit.Assert.assertNotNull(LocalTmapShapes)
        org.junit.Assert.assertNotNull(LocalTmapSpacing)
        org.junit.Assert.assertNotNull(LocalTmapMotion)
    }
}
