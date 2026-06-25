package net.qmindtech.tmap.ui.capture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
 * Quick-capture bottom sheet (P1.7) — the in-app capture surface, hosted in a [ModalBottomSheet].
 *
 * The actual content (drag handle, NL field, parsed-token chips, Today/Inbox/Priority/Remind
 * quick-actions, amber send) lives in [QuickCaptureContent] so it can be reused verbatim by the
 * home-screen Quick Capture widget's [QuickCaptureOverlay] (which can't use a ModalBottomSheet).
 *
 * On submit the field clears but the sheet stays open for rapid-fire capture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickCaptureSheet(
  onDismiss: () -> Unit,
  viewModel: QuickCaptureViewModel = hiltViewModel(),
) {
  val colors = LocalTmapColors.current
  val shapes = LocalTmapShapes.current
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

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = colors.surfaceRaised,
    dragHandle = null, // we draw our own handle in QuickCaptureContent
    shape = RoundedCornerShape(topStart = shapes.sheetTop, topEnd = shapes.sheetTop),
  ) {
    QuickCaptureContent(viewModel = viewModel)
  }
}

/**
 * Lightweight scrim + bottom-anchored card host for the **home-screen widget** quick-add (used by
 * `CaptureTrampolineActivity`). Renders the same [QuickCaptureContent] over the launcher, styled in
 * Midnight Calm — without launching the app. A [ModalBottomSheet] can't be used here because, hosted
 * in a bare translucent activity, it starts `Hidden` and immediately reports dismissed.
 *
 * Tapping the scrim or pressing back calls [onDismiss] (the activity finishes); taps on the card are
 * absorbed. The card pads for the IME + navigation bar so the field stays above the keyboard.
 */
@Composable
fun QuickCaptureOverlay(
  onDismiss: () -> Unit,
  viewModel: QuickCaptureViewModel = hiltViewModel(),
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
      QuickCaptureContent(viewModel = viewModel)
    }
  }
}

/**
 * The capture form itself (panel ② of daily-core.html): drag handle, auto-focused NL text field,
 * inline parsed-token chips (PROJECT blue-tint / DATE+TIME amber-tint / PRIORITY flag-tint),
 * divider, four quick-action FilterChips, helper hint + amber send button.
 *
 * Shared by [QuickCaptureSheet] (in-app ModalBottomSheet) and [QuickCaptureOverlay] (widget).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickCaptureContent(
  viewModel: QuickCaptureViewModel,
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
              colors.projectWork.copy(alpha = 0.18f) to colors.projectWork.copy(alpha = 0.87f)
            ParsedToken.Kind.DATE, ParsedToken.Kind.TIME ->
              colors.accent.copy(alpha = 0.18f) to colors.accent
            ParsedToken.Kind.PRIORITY ->
              colors.danger.copy(alpha = 0.18f) to colors.danger
          }
          TokenChip(text = tok.text, bgColor = bgColor, textColor = textColor)
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
      FilterChip(label = "📅 Today", selected = state.chipTodayActive, onClick = viewModel::chipToday)
      FilterChip(label = "📥 Inbox", selected = state.chipInboxActive, onClick = viewModel::chipInbox)
      FilterChip(label = "⚑ Priority", selected = state.priorityLevel > 0, onClick = viewModel::chipPriority)
      FilterChip(label = "⏰ Remind", selected = state.remind, onClick = viewModel::chipRemind)
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
            ),
          )
          .semantics { contentDescription = "Add task" },
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = null,
          tint = if (state.canSubmit) colors.onAccent else colors.textTertiary,
        )
      }
    }
  }

  // Auto-focus the text field when the capture surface appears.
  LaunchedEffect(Unit) {
    focusRequester.requestFocus()
  }
}

/**
 * Display-only tinted chip for showing a single parsed token inline. Not clickable.
 */
@Composable
private fun TokenChip(
  text: String,
  bgColor: Color,
  textColor: Color,
) {
  val chipCorner = 6.dp
  val type = LocalTmapType.current
  Box(
    modifier = Modifier
      .background(color = bgColor, shape = RoundedCornerShape(chipCorner))
      .padding(horizontal = 6.dp, vertical = 1.dp),
  ) {
    Text(text = text, style = type.meta, color = textColor)
  }
}
