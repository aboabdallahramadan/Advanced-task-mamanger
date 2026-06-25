package net.qmindtech.tmap.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

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

/** True when the user has disabled system animations (accessibility). Provided by TmapTheme. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Reads the system animator-duration scale and maps it to the reduce-motion flag. */
@Composable
fun rememberReduceMotion(): Boolean {
    val scale = Settings.Global.getFloat(
        LocalContext.current.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return reducedMotion(scale)
}

/** A standard-eased tween whose duration collapses to 0 under reduce-motion. */
@Composable
fun tmapTween(baseMillis: Int = LocalTmapMotion.current.standardMillis): TweenSpec<Float> =
    tween(durationMillis = effectiveDurationMillis(baseMillis, LocalReduceMotion.current), easing = standardEasing())
