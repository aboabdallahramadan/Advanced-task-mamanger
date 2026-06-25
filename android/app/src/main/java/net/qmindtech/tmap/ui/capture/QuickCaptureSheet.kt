package net.qmindtech.tmap.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
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
import net.qmindtech.tmap.ui.components.FilterChip
import net.qmindtech.tmap.ui.theme.LocalReduceMotion
import net.qmindtech.tmap.ui.theme.LocalTmapColors
import net.qmindtech.tmap.ui.theme.LocalTmapShapes
import net.qmindtech.tmap.ui.theme.LocalTmapType

/**
 * Quick-capture bottom sheet (P1.7).
 *
 * Layout (panel ② of daily-core.html):
 *  - drag handle
 *  - single text field (auto-focused) bound to VM text
 *  - inline parsed-token chips (PROJECT blue-tint / DATE+TIME amber-tint / PRIORITY flag-tint)
 *  - horizontal divider
 *  - four quick-action FilterChips: Today / Inbox / Priority / Remind (highlighted when active)
 *  - helper hint + amber rounded send button
 *
 * On submit the field clears but the sheet stays open for rapid-fire capture.
 *
 * Accessibility: send button has a contentDescription; chips use role=Button via FilterChip.
 * RTL: all paddings use start/end; layout is start-to-end.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickCaptureSheet(
  onDismiss: () -> Unit,
  viewModel: QuickCaptureViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsStateWithLifecycle()
  val colors = LocalTmapColors.current
  val shapes = LocalTmapShapes.current
  val type = LocalTmapType.current
  val reduceMotion = LocalReduceMotion.current
  val density = LocalDensity.current
  // Under reduce-motion start in Expanded so the sheet appears instantly (no slide-up).
  val sheetState = remember(reduceMotion, density) {
    SheetState(
      skipPartiallyExpanded = true,
      density = density,
      initialValue = if (reduceMotion) SheetValue.Expanded else SheetValue.Hidden,
    )
  }
  val focusRequester = remember { FocusRequester() }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = colors.surfaceRaised,
    dragHandle = null,   // we draw our own handle below
    shape = RoundedCornerShape(topStart = shapes.sheetTop, topEnd = shapes.sheetTop),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
    ) {
      // ── Drag handle ──────────────────────────────────────────────────────────
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 12.dp, bottom = 16.dp),
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

      // ── Text field ───────────────────────────────────────────────────────────
      OutlinedTextField(
        value = state.text,
        onValueChange = viewModel::onTextChange,
        modifier = Modifier
          .fillMaxWidth()
          .focusRequester(focusRequester),
        placeholder = {
          Text(
            text = "What needs doing?",
            style = type.body,
            color = colors.textTertiary,
          )
        },
        textStyle = type.body.copy(color = colors.textPrimary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = colors.accent,
          unfocusedBorderColor = colors.borderSubtle,
          cursorColor = colors.accent,
          focusedContainerColor = Color.Transparent,
          unfocusedContainerColor = Color.Transparent,
        ),
      )

      // ── Inline parsed-token chips ────────────────────────────────────────────
      if (state.parsed.tokens.isNotEmpty()) {
        FlowRow(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          state.parsed.tokens.forEach { tok ->
            val (bgColor, textColor) = when (tok.kind) {
              ParsedToken.Kind.PROJECT ->
                // blue-tint: projectWork at 18% alpha bg, projectWork text
                colors.projectWork.copy(alpha = 0.18f) to colors.projectWork.copy(alpha = 0.87f)
              ParsedToken.Kind.DATE, ParsedToken.Kind.TIME ->
                // amber-tint: accent at 18% alpha bg, accent text
                colors.accent.copy(alpha = 0.18f) to colors.accent
              ParsedToken.Kind.PRIORITY ->
                // flag-tint: danger at 18% alpha bg, danger text
                colors.danger.copy(alpha = 0.18f) to colors.danger
            }
            TokenChip(
              text = tok.text,
              bgColor = bgColor,
              textColor = textColor,
            )
          }
        }
      }

      // ── Divider ──────────────────────────────────────────────────────────────
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 16.dp, bottom = 2.dp)
          .height(1.dp)
          .background(colors.borderStrong),
      )

      // ── Quick-action FilterChips row ─────────────────────────────────────────
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        FilterChip(
          label = "📅 Today",
          selected = state.chipTodayActive,
          onClick = viewModel::chipToday,
        )
        FilterChip(
          label = "📥 Inbox",
          selected = state.chipInboxActive,
          onClick = viewModel::chipInbox,
        )
        FilterChip(
          label = "⚑ Priority",
          selected = state.priorityLevel > 0,
          onClick = viewModel::chipPriority,
        )
        FilterChip(
          label = "⏰ Remind",
          selected = state.remind,
          onClick = viewModel::chipRemind,
        )
      }

      // ── Hint + Send button ───────────────────────────────────────────────────
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Type naturally — it parses #project, dates & !priority",
          style = type.meta,
          color = colors.textTertiary,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(10.dp))
        // Amber gradient rounded send button (46×46 dp, borderRadius 13dp per spec)
        IconButton(
          onClick = { viewModel.submit() },
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
              )
            )
            .semantics { contentDescription = "Add task" },
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null, // described on the button itself
            tint = if (state.canSubmit) colors.onAccent else colors.textTertiary,
          )
        }
      }
    }
  }

  // Auto-focus the text field when the sheet appears
  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }
}

/**
 * Display-only tinted chip for showing a single parsed token inline.
 * Not clickable — purely informational.
 */
@Composable
private fun TokenChip(
  text: String,
  bgColor: Color,
  textColor: Color,
) {
  val chipCorner = 6.dp
  val shapes = LocalTmapShapes.current
  val type = LocalTmapType.current
  Box(
    modifier = Modifier
      .background(color = bgColor, shape = RoundedCornerShape(chipCorner))
      .padding(horizontal = 6.dp, vertical = 1.dp),
  ) {
    Text(
      text = text,
      style = type.meta,
      color = textColor,
    )
  }
}
