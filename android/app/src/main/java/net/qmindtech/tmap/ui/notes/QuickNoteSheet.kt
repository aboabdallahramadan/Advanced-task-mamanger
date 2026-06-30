package net.qmindtech.tmap.ui.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Scrim + bottom-card host for the Quick-add Note widget, mirroring
 * [net.qmindtech.tmap.ui.capture.QuickCaptureOverlay] but for a single free-text note. Rendered over
 * the launcher by [net.qmindtech.tmap.widget.NoteCaptureTrampolineActivity] (no full app launch).
 * Tapping the scrim / pressing back calls [onDismiss]; a successful save calls [onSaved].
 */
@Composable
fun QuickNoteOverlay(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: QuickNoteViewModel = hiltViewModel(),
) {
    val colors = LocalTmapColors.current
    val shapes = LocalTmapShapes.current
    val scrimSource = remember { MutableInteractionSource() }
    val cardSource = remember { MutableInteractionSource() }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = scrimSource, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = shapes.sheetTop, topEnd = shapes.sheetTop))
                .background(colors.surfaceRaised)
                // Absorb taps so they don't fall through to the dismiss scrim.
                .clickable(interactionSource = cardSource, indication = null, onClick = {})
                .navigationBarsPadding()
                .imePadding(),
        ) {
            QuickNoteContent(viewModel = viewModel, onSaved = onSaved)
        }
    }
}

/**
 * The note-entry form: drag handle, auto-focused multi-line text field, amber send button. Mirrors
 * the styling of [net.qmindtech.tmap.ui.capture.QuickCaptureContent] without the NL parsing or
 * quick-action chips. Enter inserts a newline; saving is via the amber button.
 */
@Composable
fun QuickNoteContent(
    viewModel: QuickNoteViewModel,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalTmapColors.current
    val type = LocalTmapType.current
    val shapes = LocalTmapShapes.current
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
    ) {
        // Drag handle
        Box(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
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

        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text(text = "Write a note…", style = type.body, color = colors.textTertiary) },
            textStyle = type.body.copy(color = colors.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.borderSubtle,
                cursorColor = colors.accent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "First line becomes the title",
                style = type.meta,
                color = colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(10.dp))
            IconButton(
                onClick = { viewModel.submit(onSaved) },
                enabled = state.canSubmit,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(shapes.button))
                    .background(
                        brush = Brush.linearGradient(
                            colors = if (state.canSubmit) {
                                listOf(colors.accent, colors.accentEnd)
                            } else {
                                listOf(colors.borderStrong, colors.borderSubtle)
                            },
                        ),
                    )
                    .semantics { contentDescription = "Save note" },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (state.canSubmit) colors.onAccent else colors.textTertiary,
                )
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}
