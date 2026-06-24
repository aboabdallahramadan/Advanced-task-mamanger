package net.qmindtech.tmap.ui.planning

import net.qmindtech.tmap.data.local.entities.TaskEntity

enum class PlanningStep {
    Reflect, TriageInbox, PickToday, Timebox;

    /** 1-based position for the "STEP n OF 4" eyebrow. */
    val stepNumber: Int get() = ordinal + 1

    fun next(): PlanningStep = entries.getOrElse(ordinal + 1) { this }
    fun back(): PlanningStep = entries.getOrElse(ordinal - 1) { this }

    /** Amber eyebrow text, e.g. "STEP 3 OF 4 · PICK YOUR DAY". */
    val eyebrow: String get() = "STEP $stepNumber OF $STEP_COUNT · $label"

    /** Big heading per the mockup. */
    val heading: String get() = when (this) {
        Reflect -> "How did yesterday go?"
        TriageInbox -> "Clear your inbox"
        PickToday -> "What deserves your time today?"
        Timebox -> "Block out your day"
    }

    private val label: String get() = when (this) {
        Reflect -> "REFLECT"
        TriageInbox -> "TRIAGE INBOX"
        PickToday -> "PICK YOUR DAY"
        Timebox -> "TIMEBOX"
    }

    companion object {
        const val STEP_COUNT = 4
        val FIRST: PlanningStep get() = Reflect
        val LAST: PlanningStep get() = Timebox
    }
}

/** Per-row projection for the Reflect / PickToday / Timebox lists. */
data class PlanItemUi(
    val id: String,
    val title: String,
    val projectName: String?,
    val projectColor: Long?,
    val durationMinutes: Int?,   // null = use DEFAULT_TASK_MINUTES estimate
    val done: Boolean = false,   // only meaningful in Reflect (yesterday's status)
    val added: Boolean = false,  // PickToday: already in the pick set
)

data class PlanningUiState(
    val loading: Boolean = true,
    val step: PlanningStep = PlanningStep.Reflect,
    // Reflect:
    val yesterdayDone: List<PlanItemUi> = emptyList(),
    val yesterdayUndone: List<PlanItemUi> = emptyList(),
    // TriageInbox:
    val inbox: List<PlanItemUi> = emptyList(),
    // PickToday (carry-over = yesterday's undone; inboxPicks = inbox items; pick = ordered chosen set):
    val carryOver: List<PlanItemUi> = emptyList(),
    val inboxPicks: List<PlanItemUi> = emptyList(),
    val pickedIds: List<String> = emptyList(),      // ordered; the day's plannedTaskIds
    // Capacity (live):
    val plannedMinutes: Int = 0,
    val workdayMinutes: Int = DEFAULT_WORKDAY_MINUTES,
    val committed: Boolean = false,
) {
    val isFirstStep: Boolean get() = step == PlanningStep.FIRST
    val isLastStep: Boolean get() = step == PlanningStep.LAST
    val capacityFraction: Float get() = capacityFraction(plannedMinutes, workdayMinutes)
}

/** Pure transitions — clamp at the ends. */
fun PlanningUiState.advance(): PlanningUiState = copy(step = step.next())
fun PlanningUiState.retreat(): PlanningUiState = copy(step = step.back())
