package net.qmindtech.tmap.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner-radius tokens (spec §4.2). */
@Immutable
data class TmapShapes(
    val card: Dp,
    val sheetTop: Dp,
    val pill: Dp,
    val button: Dp,
    val well: Dp,
)

val TmapDefaultShapes: TmapShapes = TmapShapes(
    card = 18.dp,
    sheetTop = 26.dp,
    pill = 999.dp,
    button = 13.dp,
    well = 12.dp,
)

val LocalTmapShapes = staticCompositionLocalOf { TmapDefaultShapes }
