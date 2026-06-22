package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
/** Small round project color marker (mockup's colored dot before the meta line). */
@Composable
fun ProjectDot(colorArgb: Long, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier = modifier.size(size).background(Color(colorArgb), CircleShape))
}

/** Rounded color swatch with an optional emoji — for project cards/pickers. */
@Composable
fun ProjectSwatch(colorArgb: Long, emoji: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(34.dp)
            .background(Color(colorArgb), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!emoji.isNullOrBlank()) Text(emoji)
    }
}
