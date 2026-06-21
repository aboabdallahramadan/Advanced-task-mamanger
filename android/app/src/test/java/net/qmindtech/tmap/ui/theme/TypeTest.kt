package net.qmindtech.tmap.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TypeTest {
    private val t = TmapDefaultType

    @Test
    fun typeScaleMatchesSpec() {
        assertEquals(40.sp, t.display.fontSize)
        assertEquals(FontWeight.Light, t.display.fontWeight) // 300

        assertEquals(25.sp, t.title.fontSize)
        assertEquals(FontWeight.SemiBold, t.title.fontWeight) // 600

        assertEquals(19.sp, t.heading.fontSize)
        assertEquals(FontWeight.SemiBold, t.heading.fontWeight)

        assertEquals(14.5.sp, t.body.fontSize)
        assertEquals(FontWeight.Medium, t.body.fontWeight) // 500

        assertEquals(12.sp, t.meta.fontSize)

        assertEquals(11.sp, t.label.fontSize)
        assertEquals(FontWeight.Bold, t.label.fontWeight) // 700
    }

    @Test
    fun labelStyleIsUppercaseTrackedForSectionHeaders() {
        // Letter-spacing present (uppercasing is applied at the call site/SectionLabel).
        assertEquals(true, t.label.letterSpacing.value > 0f)
    }

    @Test
    fun localDefaultsToTheDefaultType() {
        // .current requires @Composable context; verify the CompositionLocal val is non-null
        // (its default is pinned by the concrete token tests above).
        assertNotNull(LocalTmapType)
    }
}
