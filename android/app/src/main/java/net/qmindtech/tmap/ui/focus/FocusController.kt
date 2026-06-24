package net.qmindtech.tmap.ui.focus

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.repository.FocusSessionRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.util.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives a single focus interval and the pomodoro session counter (spec §6.5). It is the SOLE
 * producer of [state]; the foreground [FocusService] (P6.5) keeps the process alive while
 * [FocusState.isActive], and [FocusViewModel] (P6.6) maps [state] to the UI.
 *
 * The countdown runs `delay(1000)` in a loop on an INJECTED [dispatcher] so tests pass a
 * StandardTestDispatcher and virtualize all delays (a 25-minute interval ticks in zero real time);
 * production injects Dispatchers.Default (P6.5). On reaching zero, [onIntervalComplete] (P6.3)
 * persists the session and the task time; pause/resume/end (P6.4) gate the loop.
 *
 * Singleton-scoped so the timer survives a screen leaving composition (the service holds the process).
 */
@Singleton
class FocusController @Inject constructor(
    private val focusSessions: FocusSessionRepository,
    private val tasks: TaskRepository,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private val _state = MutableStateFlow(FocusState())
    val state: StateFlow<FocusState> = _state.asStateFlow()

    private var tickJob: Job? = null
    private var intervalStartedAt: Instant = Instant.EPOCH

    /** FIXED contract. Begins a fresh interval, cancelling any in-flight one. */
    fun start(taskId: String?, project: String, lengthMin: Int) {
        tickJob?.cancel()
        intervalStartedAt = clock.now()
        _state.update {
            it.copy(
                phase = FocusPhase.Running,
                taskId = taskId,
                project = project,
                lengthMin = lengthMin,
                remainingSeconds = lengthMin * 60,
            )
        }
        launchTicker()
    }

    private fun launchTicker() {
        tickJob = scope.launch {
            while (isActive && _state.value.remainingSeconds > 0) {
                delay(1_000)
                // Guard AFTER the delay: pause() sets phase = Paused so this check skips the
                // decrement immediately on the next tick boundary (max 1 s lag, acceptable for
                // production; the test verifies remaining is frozen across a 10 s paused window).
                // end() cancels the Job so isActive becomes false and the loop exits cleanly.
                if (_state.value.phase != FocusPhase.Running) continue
                val next = _state.value.remainingSeconds - 1
                _state.update { it.copy(remainingSeconds = next) }
                if (next <= 0) {
                    onIntervalComplete()
                    break
                }
            }
        }
    }

    /** Persists the just-completed interval as a FocusSession, credits task time, and advances the counter (P6.3). */
    private suspend fun onIntervalComplete() {
        val s = _state.value
        val endedAt = clock.now()
        focusSessions.create(
            taskId = s.taskId,
            project = s.project,
            startedAt = intervalStartedAt,
            endedAt = endedAt,
            minutes = s.lengthMin,
            date = clock.today(),
        )
        // The backend does not auto-aggregate; mirror the focus time onto the task locally (spec §6.5).
        s.taskId?.let { tasks.addActualTime(it, s.lengthMin) }
        _state.update {
            it.copy(phase = FocusPhase.Completed, completedSessions = it.completedSessions + 1)
        }
    }

    fun pause() {
        if (_state.value.phase != FocusPhase.Running) return
        _state.update { it.copy(phase = FocusPhase.Paused) }
    }

    fun resume() {
        if (_state.value.phase != FocusPhase.Paused) return
        _state.update { it.copy(phase = FocusPhase.Running) }
    }

    /** End the interval early: cancel the ticker, drop to Idle, log nothing (spec §6.5). Idempotent. */
    fun end() {
        tickJob?.cancel()
        tickJob = null
        _state.update { it.copy(phase = FocusPhase.Idle, remainingSeconds = 0) }
    }
}
