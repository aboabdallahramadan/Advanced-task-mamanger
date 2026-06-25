package net.qmindtech.tmap.ui.components

import kotlin.math.abs
import kotlin.math.min

/** Toward-start = complete, toward-end = defer/delete (spec §6.1; caller normalises for RTL). */
enum class SwipeAction { None, Complete, DeferDelete }

data class SwipeDecision(val action: SwipeAction, val revealedProgress: Float)

/**
 * Pure swipe resolution against a *semantic* offset (always positive = toward-start = complete,
 * regardless of layout direction — callers must negate the physical offset in RTL before passing).
 * Positive offset past the threshold completes; negative offset past the threshold defers/deletes;
 * otherwise None. revealedProgress is clamped 0..1.
 */
fun resolveSwipe(offsetPx: Float, thresholdPx: Float): SwipeDecision {
    val progress = min(1f, abs(offsetPx) / thresholdPx)
    val action = when {
        offsetPx >= thresholdPx -> SwipeAction.Complete
        offsetPx <= -thresholdPx -> SwipeAction.DeferDelete
        else -> SwipeAction.None
    }
    return SwipeDecision(action, progress)
}
