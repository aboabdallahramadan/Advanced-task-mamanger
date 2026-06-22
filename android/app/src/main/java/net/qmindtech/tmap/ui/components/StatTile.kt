package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

/** A single stat (e.g. "5" / "day streak") for the You screen. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    Column(
        modifier = modifier
            .background(colors.surface, RoundedCornerShape(shapes.card))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(value, style = type.title, color = colors.accent)
        Text(label, style = type.meta, color = colors.textSecondary, modifier = Modifier.padding(top = 2.dp))
    }
}
