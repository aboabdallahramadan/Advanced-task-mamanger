package net.qmindtech.tmap.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeStateTest {
    private val threshold = 120f

    @Test
    fun belowThresholdIsNone() {
        assertEquals(SwipeAction.None, resolveSwipe(40f, threshold).action)
        assertEquals(SwipeAction.None, resolveSwipe(-40f, threshold).action)
    }

    @Test
    fun rightPastThresholdCompletes() {
        assertEquals(SwipeAction.Complete, resolveSwipe(140f, threshold).action)
    }

    @Test
    fun leftPastThresholdDefersOrDeletes() {
        assertEquals(SwipeAction.DeferDelete, resolveSwipe(-140f, threshold).action)
    }

    @Test
    fun progressIsClampedZeroToOne() {
        assertEquals(0.5f, resolveSwipe(60f, threshold).revealedProgress, 0.001f)
        assertEquals(1f, resolveSwipe(240f, threshold).revealedProgress, 0.001f)
        assertEquals(1f, resolveSwipe(-240f, threshold).revealedProgress, 0.001f)
    }

    @Test
    fun atExactThresholdTriggers() {
        assertEquals(SwipeAction.Complete, resolveSwipe(120f, threshold).action)
        assertEquals(SwipeAction.DeferDelete, resolveSwipe(-120f, threshold).action)
    }
}
