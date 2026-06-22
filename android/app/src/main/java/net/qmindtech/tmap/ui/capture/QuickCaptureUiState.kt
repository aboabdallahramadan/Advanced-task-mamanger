package net.qmindtech.tmap.ui.capture

import net.qmindtech.tmap.data.local.entities.ProjectEntity

data class QuickCaptureUiState(
  val text: String = "",
  val parsed: ParsedCapture = ParsedCapture("", null, 0, null, null, emptyList()),
  val remind: Boolean = false,
  val projects: List<ProjectEntity> = emptyList(),
  val canSubmit: Boolean = false,
)
