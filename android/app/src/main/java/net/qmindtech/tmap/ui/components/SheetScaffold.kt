package net.qmindtech.tmap.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapSpacing
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Reusable bottom-sheet shell used by quick-capture, task editor, and filter/sort pickers.
 *
 * Renders a [ModalBottomSheet] with:
 * - [surfaceRaised] container color
 * - 26 dp top corners ([TmapShapes.sheetTop])
 * - Decorative drag-handle pill (no content description — purely decorative)
 * - Optional [title] row in [TmapType.heading] style
 * - Scrim using [bgBottom] at 60 % alpha
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetScaffold(
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val shapes = LocalTmapShapes.current
    val spacing = LocalTmapSpacing.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = shapes.sheetTop, topEnd = shapes.sheetTop),
        containerColor = colors.surfaceRaised,
        scrimColor = colors.bgBottom.copy(alpha = 0.6f),
        // Hide the default M3 drag handle — we draw our own below for full token control.
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(38.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(shapes.pill))
                        .background(colors.borderStrong),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.xl)
                .padding(bottom = spacing.xl + spacing.base),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = type.heading,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(vertical = spacing.xs + spacing.base),
                )
            }
            content()
        }
    }
}
