package net.qmindtech.tmap.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale (spec §4.2): 4 base; rhythm 4/8/10/14/16/20/22; screen horizontal 16–20 (18 default). */
@Immutable
data class TmapSpacing(
    val base: Dp,
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
    val screenH: Dp,
)

val TmapDefaultSpacing: TmapSpacing = TmapSpacing(
    base = 4.dp,
    xs = 8.dp,
    sm = 10.dp,
    md = 14.dp,
    lg = 16.dp,
    xl = 20.dp,
    xxl = 22.dp,
    screenH = 18.dp,
)

val LocalTmapSpacing = staticCompositionLocalOf { TmapDefaultSpacing }
