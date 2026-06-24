package net.qmindtech.tmap.ui.focus

/** The lifecycle phase of a focus interval (spec §6.5). */
enum class FocusPhase { Idle, Running, Paused, Completed }

/**
 * Immutable focus-mode state. One [FocusState] describes the current interval: its phase, the
 * bound task (null for a task-less focus), the project name snapshot, the interval length, the
 * seconds remaining, and the session counter (e.g. "Session 2 of 4"). The controller (P6.2) is the
 * sole producer; the view-model (P6.6) maps it to UI; the ring reads [elapsedFraction].
 */
data class FocusState(
    val phase: FocusPhase = FocusPhase.Idle,
    val taskId: String? = null,
    val project: String = "",
    val lengthMin: Int = 25,
    val remainingSeconds: Int = 0,
    val completedSessions: Int = 0,
    val totalSessions: Int = 4,
)

/** Ring progress 0..1: fraction of the interval elapsed. Zero when the interval has no length. */
val FocusState.elapsedFraction: Float
    get() {
        val total = lengthMin * 60
        if (total <= 0) return 0f
        return ((total - remainingSeconds) / total.toFloat()).coerceIn(0f, 1f)
    }

/** True while a timer is in flight (running) or held (paused) — used to gate the foreground service. */
val FocusState.isActive: Boolean
    get() = phase == FocusPhase.Running || phase == FocusPhase.Paused
