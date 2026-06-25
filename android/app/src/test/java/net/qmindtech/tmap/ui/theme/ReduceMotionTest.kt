package net.qmindtech.tmap.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ReduceMotionTest {
    @Test
    fun scaleZeroMeansReduceMotion() {
        assertEquals(true, reducedMotion(0f))
    }

    @Test
    fun normalAndSlowScalesDoNotReduceMotion() {
        assertEquals(false, reducedMotion(1f))
        assertEquals(false, reducedMotion(0.5f))
        assertEquals(false, reducedMotion(10f))
    }

    @Test
    fun reduceMotionCollapsesDurationToZero() {
        assertEquals(0, effectiveDurationMillis(220, reduceMotion = true))
        assertEquals(0, effectiveDurationMillis(180, reduceMotion = true))
    }

    @Test
    fun normalMotionKeepsBaseDuration() {
        assertEquals(220, effectiveDurationMillis(220, reduceMotion = false))
        assertEquals(180, effectiveDurationMillis(180, reduceMotion = false))
    }

    // tmapTween delegates duration computation to effectiveDurationMillis; verify the pure path.
    @Test
    fun tmapTweenDurationCollapsesToZeroWhenReduceMotion() {
        assertEquals(0, effectiveDurationMillis(220, reduceMotion = true))
        assertEquals(0, effectiveDurationMillis(180, reduceMotion = true))
    }

    @Test
    fun tmapTweenDurationUsesBaseWhenNotReduceMotion() {
        assertEquals(220, effectiveDurationMillis(220, reduceMotion = false))
        assertEquals(180, effectiveDurationMillis(180, reduceMotion = false))
    }
}
