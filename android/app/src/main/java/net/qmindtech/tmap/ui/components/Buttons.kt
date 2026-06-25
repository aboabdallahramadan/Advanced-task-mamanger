package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Amber-gradient primary action button (Midnight Calm spec).
 *
 * Fill: 135° accent→accentEnd gradient (or [TmapColorScheme.surfaceRaised] when disabled).
 * Text: [TmapColorScheme.onAccent] when enabled, [TmapColorScheme.textTertiary] when disabled.
 * Corner radius: [TmapShapes.button] (13 dp).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    val shape = RoundedCornerShape(shapes.button)
    val brush = Brush.linearGradient(listOf(colors.accent, colors.accentEnd))
    Box(
        modifier = modifier
            .then(
                if (enabled) Modifier.background(brush = brush, shape = shape)
                else Modifier.background(colors.surfaceRaised, shape)
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.body,
            color = if (enabled) colors.onAccent else colors.textTertiary,
        )
    }
}

/**
 * Secondary/ghost button with a [TmapColorScheme.surfaceRaised] fill and [TmapColorScheme.borderStrong] outline.
 *
 * Text: [TmapColorScheme.textPrimary] when enabled, [TmapColorScheme.textTertiary] when disabled.
 * Corner radius: [TmapShapes.button] (13 dp).
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val type = LocalTmapType.current
    val shape = RoundedCornerShape(shapes.button)
    Box(
        modifier = modifier
            .background(colors.surfaceRaised, shape)
            .border(1.dp, colors.borderStrong, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = type.body,
            color = if (enabled) colors.textPrimary else colors.textTertiary,
        )
    }
}

/**
 * Quick-capture FAB — FIXED contract consumed by every tab scaffold.
 *
 * A 56 dp amber-gradient circle ([TmapColorScheme.accent]→[TmapColorScheme.accentEnd] at 135°)
 * with a centred `+` icon ([TmapColorScheme.onAccent]) and a soft diffuse amber shadow.
 *
 * @param onClick   Invoked when tapped.
 * @param modifier  Optional outer modifier (position, offset, etc.).
 */
@Composable
fun TmapFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalTmapColors.current
    val brush = Brush.linearGradient(listOf(colors.accent, colors.accentEnd))
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(elevation = 12.dp, shape = CircleShape, clip = false)
            .background(brush, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Quick capture",
            tint = colors.onAccent,
        )
    }
}
