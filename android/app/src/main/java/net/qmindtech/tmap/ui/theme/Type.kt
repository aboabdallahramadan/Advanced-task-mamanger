package net.qmindtech.tmap.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Midnight Calm type scale (spec §4.2), system font.
 * display 40/300 · title 25/600 · heading 19–20/600 · body 14.5/500 · meta 12 · label 11/700 uppercase+ls.
 */
@Immutable
data class TmapType(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    val meta: TextStyle,
    val label: TextStyle,
)

private val Sans = FontFamily.SansSerif

val TmapDefaultType: TmapType = TmapType(
    display = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 46.sp),
    title = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 30.sp),
    heading = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp),
    body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.5.sp, lineHeight = 20.sp),
    meta = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.sp),
)

val LocalTmapType = staticCompositionLocalOf { TmapDefaultType }

/**
 * Material3 typography bridge so M3 components (ripple defaults, fallbacks) read sensible styles.
 * App UI prefers the TmapType tokens above; this is the safety net.
 */
val TmapMaterialTypography: Typography = Typography(
    titleLarge = TmapDefaultType.title,
    titleMedium = TmapDefaultType.heading,
    bodyLarge = TmapDefaultType.body,
    bodyMedium = TmapDefaultType.body,
    labelLarge = TmapDefaultType.meta,
    labelSmall = TmapDefaultType.label,
)
