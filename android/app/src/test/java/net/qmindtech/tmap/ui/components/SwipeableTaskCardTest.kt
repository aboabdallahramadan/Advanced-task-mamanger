package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.TmapTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-backed Compose UI test guarding FIX C1: the swipe gesture must read the *live*
 * remembered offset in onDragEnd (not a recomposition-captured value frozen at offset==0f).
 *
 * Before the fix the pointerInput closure resolved swipes against a stale ~0f offset and always
 * returned SwipeAction.None, so the card only snapped back and neither callback ever fired. These
 * tests drag past the 96.dp threshold and assert the correct callback fires.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwipeableTaskCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val task = TaskUi(
        id = "t1",
        title = "Design review",
        projectName = null,
        projectColor = null,
        scheduledLabel = null,
        subtaskDone = 0,
        subtaskTotal = 0,
        priority = 0,
        hasReminder = false,
        isDone = false,
    )

    private class Captures {
        var completed = false
        var deferred = false
        var deleted = false
    }

    private fun setContent(caps: Captures) {
        composeRule.setContent {
            TmapTheme {
                SwipeableTaskCard(
                    task = task,
                    onToggleComplete = { caps.completed = true },
                    onDefer = { caps.deferred = true },
                    onDelete = { caps.deleted = true },
                    onClick = {},
                    // Wide enough that a full-width swipe clears the 96.dp threshold.
                    modifier = Modifier.width(400.dp),
                )
            }
        }
    }

    @Test
    fun swipeRightPastThresholdFiresComplete() {
        val caps = Captures()
        setContent(caps)

        composeRule.onNodeWithText("Design review").performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertTrue("swipe-right past threshold should complete", caps.completed)
        assertFalse(caps.deferred)
    }

    @Test
    fun swipeLeftPastThresholdFiresDefer() {
        val caps = Captures()
        setContent(caps)

        composeRule.onNodeWithText("Design review").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertTrue("swipe-left past threshold should defer", caps.deferred)
        assertFalse(caps.completed)
    }

    @Test
    fun shortSwipeBelowThresholdFiresNothing() {
        val caps = Captures()
        setContent(caps)

        // A tiny drag well under the 96.dp threshold must snap back and fire no callback.
        composeRule.onNodeWithText("Design review").performTouchInput {
            swipeRight(startX = center.x, endX = center.x + 20f)
        }
        composeRule.waitForIdle()

        assertFalse(caps.completed)
        assertFalse(caps.deferred)
        assertFalse(caps.deleted)
    }
}
