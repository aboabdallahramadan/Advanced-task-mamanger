package net.qmindtech.tmap.ui.focus

/** The view-state the FocusScreen renders (spec §6.5, mockup "Focusing On"). */
data class FocusUiState(
    val phase: FocusPhase = FocusPhase.Idle,
    val taskTitle: String? = null,
    val project: String = "",
    val progress: Float = 0f,
    val remainingLabel: String = "00:00",
    val ofLabel: String = "of 00:00",
    val completedSessions: Int = 0,
    val totalSessions: Int = 4,
    val queuedCount: Int = 0,
)
