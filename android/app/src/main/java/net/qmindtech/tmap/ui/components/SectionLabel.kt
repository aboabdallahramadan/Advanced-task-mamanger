package net.qmindtech.tmap.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapType

/** Uppercase tracked section header, e.g. "THIS MORNING" (mockup direction A). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    Text(
        text = text.uppercase(),
        style = type.label,
        color = colors.textTertiary,
        modifier = modifier,
    )
}
