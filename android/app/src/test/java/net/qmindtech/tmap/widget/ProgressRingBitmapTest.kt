package net.qmindtech.tmap.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressRingBitmapTest {
    @Test fun `sweep is 0 at 0 percent`() {
        assertEquals(0f, ProgressRingBitmap.sweepDegrees(0f), 0.001f)
    }
    @Test fun `sweep is 360 at full`() {
        assertEquals(360f, ProgressRingBitmap.sweepDegrees(1f), 0.001f)
    }
    @Test fun `sweep is 180 at half and clamps over 1`() {
        assertEquals(180f, ProgressRingBitmap.sweepDegrees(0.5f), 0.001f)
        assertEquals(360f, ProgressRingBitmap.sweepDegrees(1.5f), 0.001f)
    }
    @Test fun `sweep clamps negatives to 0`() {
        assertEquals(0f, ProgressRingBitmap.sweepDegrees(-0.2f), 0.001f)
    }
}
