package net.qmindtech.tmap.ui.you

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.data.repository.DailyPlanRepository
import net.qmindtech.tmap.data.repository.FocusSessionRepository
import net.qmindtech.tmap.data.repository.TaskRepository
import net.qmindtech.tmap.data.stats.StatsCalculator
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.data.sync.SyncStatusHolder
import net.qmindtech.tmap.util.Clock
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Named

/** Indirection so the VM never re-implements teardown — bound to AuthRepository.logout() in DI (§5.3). */
fun interface SignOutAction { suspend operator fun invoke() }

@HiltViewModel
class YouViewModel @Inject constructor(
    authRepository: AuthRepository,
    taskRepository: TaskRepository,
    focusSessionRepository: FocusSessionRepository,
    dailyPlanRepository: DailyPlanRepository,
    syncStatusHolder: SyncStatusHolder,
    @Named("pendingCount") pendingCount: Flow<Int>,
    private val stats: StatsCalculator,
    private val signOut: SignOutAction,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
) : ViewModel() {

    private val today = clock.today()
    private val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private val weekEnd = weekStart.plusDays(6)
    private val streakWindowStart = today.minusDays(60)

    val uiState: StateFlow<YouUiState> =
        combine(
            authRepository.session,
            taskRepository.observeAll(),
            focusSessionRepository.observeForDateRange(weekStart, weekEnd),
            dailyPlanRepository.observeRange(streakWindowStart, today),
            combine(syncStatusHolder.status, pendingCount) { status, pending -> status to pending },
        ) { session, tasks, sessions, plans, syncPair ->
            val email = (session as? SessionState.Authenticated)?.email
            YouUiState(
                loading = false,
                profile = deriveProfile(email),
                dayStreak = stats.dayStreak(tasks, plans),
                doneThisWeek = stats.doneThisWeek(tasks),
                focusHoursLabel = formatFocusHours(stats.focusMinutesThisWeek(sessions)),
                todayProgress = stats.todayProgress(tasks),
                syncStatus = syncPair.first,
                pendingCount = syncPair.second,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YouUiState())

    /** Delegates to the bound AuthRepository.logout() teardown — no re-implementation here. */
    fun onSignOut() {
        viewModelScope.launch { signOut() }
    }

    fun onRetrySync() {
        syncScheduler.requestExpeditedSync()
    }
}
