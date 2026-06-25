package net.qmindtech.tmap.ui.theme

/**
 * Reduce-motion gate (spec §9 / §4.2 "respects reduce motion"). The system exposes
 * Settings.Global.ANIMATOR_DURATION_SCALE; a value of 0f means the user disabled animations.
 * These pure helpers keep the decision JVM-testable; the actual setting read + LocalReduceMotion
 * provider live at the Compose call site (P10.2).
 */
fun reducedMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f

/** Collapse any tween duration to 0 (instant) when reduce-motion is active. */
fun effectiveDurationMillis(baseMillis: Int, reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else baseMillis
