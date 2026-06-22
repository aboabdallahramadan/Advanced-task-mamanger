package net.qmindtech.tmap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressRingTest {
    @Test
    fun sweepIsProportionalToProgress() {
        assertEquals(0f, sweepAngle(0f), 0.001f)
        assertEquals(180f, sweepAngle(0.5f), 0.001f)
        assertEquals(360f, sweepAngle(1f), 0.001f)
    }

    @Test
    fun progressIsClamped() {
        assertEquals(0f, sweepAngle(-0.3f), 0.001f)
        assertEquals(360f, sweepAngle(1.4f), 0.001f)
    }
}
