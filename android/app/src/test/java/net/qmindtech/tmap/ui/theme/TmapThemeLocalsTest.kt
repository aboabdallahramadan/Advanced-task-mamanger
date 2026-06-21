package net.qmindtech.tmap.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Compose UI test: renders TmapTheme {} and asserts that all five
 * CompositionLocals resolve to the correct Midnight Calm defaults inside the theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmapThemeLocalsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tmapThemeProvidesAllFiveLocalsWithMidnightCalmDefaults() {
        var capturedColors: TmapColorScheme? = null
        var capturedShapes: TmapShapes? = null
        var capturedSpacing: TmapSpacing? = null
        var capturedMotion: TmapMotion? = null
        var capturedType: TmapType? = null

        composeRule.setContent {
            TmapTheme {
                capturedColors = LocalTmapColors.current
                capturedShapes = LocalTmapShapes.current
                capturedSpacing = LocalTmapSpacing.current
                capturedMotion = LocalTmapMotion.current
                capturedType = LocalTmapType.current
            }
        }

        assertEquals(MidnightCalmColors, capturedColors)
        assertEquals(TmapDefaultShapes, capturedShapes)
        assertEquals(TmapDefaultSpacing, capturedSpacing)
        assertEquals(TmapDefaultMotion, capturedMotion)
        assertEquals(TmapDefaultType, capturedType)
    }
}
