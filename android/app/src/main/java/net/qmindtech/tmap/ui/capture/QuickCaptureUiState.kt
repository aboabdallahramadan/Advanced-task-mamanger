package net.qmindtech.tmap.ui.capture

import net.qmindtech.tmap.data.local.entities.ProjectEntity

data class QuickCaptureUiState(
  val text: String = "",
  val parsed: ParsedCapture = ParsedCapture("", null, 0, null, null, emptyList()),
  val remind: Boolean = false,
  val projects: List<ProjectEntity> = emptyList(),
  val canSubmit: Boolean = false,
  /** True when the "Today" chip override is active. */
  val chipTodayActive: Boolean = false,
  /** True when the "Inbox" chip override is active (clear-date). */
  val chipInboxActive: Boolean = false,
  /**
   * Current priority level driven by the "Priority" chip (0 = inactive/none).
   * The chip is visually active when priorityLevel > 0.
   */
  val priorityLevel: Int = 0,
)
