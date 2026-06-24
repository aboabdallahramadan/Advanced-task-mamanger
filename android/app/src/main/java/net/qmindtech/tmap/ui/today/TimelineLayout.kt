package net.qmindtech.tmap.ui.today

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/**
 * Pure timeline geometry for the Today Timeline rail. No Compose runtime / Android deps — only the
 * [Dp] value class — so the offset/height/now-line/drop math is JVM-unit-testable and deterministic.
 * Mirrors the `full-app.html` "Today · Timeline" mockup: rail starts at 09:00 at 72dp/hour.
 */
object TimelineDefaults {
    const val RAIL_START_HOUR: Int = 9
    const val RAIL_END_HOUR: Int = 22
    val hourHeight: Dp = 72.dp
    val minBlockHeight: Dp = 36.dp
    val railHeight: Dp = ((RAIL_END_HOUR - RAIL_START_HOUR) * 72).dp
}

/** Minutes between the rail-start hour and [start]; negative (before rail start) clamps to 0. */
private fun minutesFromRailStart(start: LocalTime, startHour: Int): Int {
    val minutes = (start.hour - startHour) * 60 + start.minute
    return if (minutes < 0) 0 else minutes
}

/** Vertical offset of a block whose local start time is [start], measured from the rail top. */
fun blockOffsetDp(
    start: LocalTime,
    startHour: Int = TimelineDefaults.RAIL_START_HOUR,
    hourHeight: Dp = TimelineDefaults.hourHeight,
): Dp = (minutesFromRailStart(start, startHour) / 60f * hourHeight.value).dp

/** Rendered height of a block of [durationMinutes] (null → 60), clamped up to [minHeight]. */
fun blockHeightDp(
    durationMinutes: Int?,
    hourHeight: Dp = TimelineDefaults.hourHeight,
    minHeight: Dp = TimelineDefaults.minBlockHeight,
): Dp {
    val minutes = durationMinutes ?: 60
    val rawDp = minutes / 60f * hourHeight.value
    return if (rawDp < minHeight.value) minHeight else rawDp.dp
}
