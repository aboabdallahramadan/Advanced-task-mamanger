package net.qmindtech.tmap.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion tokens (spec §4.2): standard 220ms ease for sheet/nav; check-off ~180ms.
 * Spring on swipe/drag is applied at the call site. Callers gate on reduce-motion
 * (Settings.Global.ANIMATOR_DURATION_SCALE) — see P10.
 */
@Immutable
data class TmapMotion(
    val standardMillis: Int,
    val checkOffMillis: Int,
)

val TmapDefaultMotion: TmapMotion = TmapMotion(
    standardMillis = 220,
    checkOffMillis = 180,
)

fun standardEasing(): Easing = FastOutSlowInEasing

val LocalTmapMotion = staticCompositionLocalOf { TmapDefaultMotion }
