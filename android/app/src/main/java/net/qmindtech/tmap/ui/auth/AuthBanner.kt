package net.qmindtech.tmap.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

// Mirrors the desktop role="alert" banner: assertive for errors, polite for the
// transient network notice. Error uses the danger role; network uses the warning tone.
@Composable
fun AuthBanner(text: String, isError: Boolean) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val shapes = LocalTmapShapes.current
    val containerColor = if (isError) colors.danger.copy(alpha = 0.15f) else colors.surfaceRaised
    val textColor = if (isError) colors.danger else colors.textSecondary
    Text(
        text = text,
        style = type.meta,
        color = textColor,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(containerColor, RoundedCornerShape(shapes.well))
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
