package net.qmindtech.tmap.ui.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusStateTest {

    @Test
    fun `defaults are an idle 25-minute four-session focus`() {
        val s = FocusState()
        assertEquals(FocusPhase.Idle, s.phase)
        assertEquals(25, s.lengthMin)
        assertEquals(0, s.remainingSeconds)
        assertEquals(0, s.completedSessions)
        assertEquals(4, s.totalSessions)
        assertEquals(null, s.taskId)
    }

    @Test
    fun `elapsedFraction is zero at full remaining and one at zero remaining`() {
        val full = FocusState(lengthMin = 25, remainingSeconds = 25 * 60)
        val empty = FocusState(lengthMin = 25, remainingSeconds = 0)
        val half = FocusState(lengthMin = 10, remainingSeconds = 300) // 5 of 10 min elapsed
        assertEquals(0f, full.elapsedFraction, 0.0001f)
        assertEquals(1f, empty.elapsedFraction, 0.0001f)
        assertEquals(0.5f, half.elapsedFraction, 0.0001f)
    }

    @Test
    fun `elapsedFraction is clamped and zero when length is non-positive`() {
        assertEquals(0f, FocusState(lengthMin = 0, remainingSeconds = 0).elapsedFraction, 0.0001f)
        // Over-elapsed (remaining negative) clamps to 1.
        assertEquals(1f, FocusState(lengthMin = 5, remainingSeconds = -60).elapsedFraction, 0.0001f)
    }

    @Test
    fun `isActive is true only while running or paused`() {
        assertFalse(FocusState(phase = FocusPhase.Idle).isActive)
        assertTrue(FocusState(phase = FocusPhase.Running).isActive)
        assertTrue(FocusState(phase = FocusPhase.Paused).isActive)
        assertFalse(FocusState(phase = FocusPhase.Completed).isActive)
    }
}
