package net.qmindtech.tmap.ui.you

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.auth.AuthRepository
import net.qmindtech.tmap.data.auth.SessionState
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.local.entities.DailyPlanEntity
import net.qmindtech.tmap.data.local.entities.FocusSessionEntity
import net.qmindtech.tmap.data.repository.DailyPlanRepository
import net.qmindtech.tmap.data.repository.FocusSessionRepository
import net.qmindtech.tmap.data.stats.StatsCalculator
import net.qmindtech.tmap.data.sync.SyncScheduler
import net.qmindtech.tmap.data.sync.SyncStatus
import net.qmindtech.tmap.data.sync.SyncStatusHolder
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class YouViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val today = LocalDate.of(2026, 6, 18)
    private val clock = FixedClock(Instant.parse("2026-06-18T12:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeAuth(initial: SessionState) : AuthRepository {
        val sessionFlow = MutableStateFlow(initial)
        override val session = sessionFlow
        var logoutCalls = 0
        override suspend fun register(email: String, password: String) = Result.success(Unit)
        override suspend fun login(email: String, password: String) = Result.success(Unit)
        override suspend fun logout() { logoutCalls++ }
        override suspend fun loadSession() {}
        override suspend fun refreshBlocking() = true
    }

    private class FakeFocusRepo(val flow: MutableStateFlow<List<FocusSessionEntity>>) : FocusSessionRepository {
        override suspend fun create(taskId: String?, project: String, startedAt: Instant, endedAt: Instant, minutes: Int, date: LocalDate) = "f"
        override fun observeForTask(taskId: String): Flow<List<FocusSessionEntity>> = flow
        override fun observeForDateRange(start: LocalDate, end: LocalDate): Flow<List<FocusSessionEntity>> = flow
    }

    private class FakeDailyPlanRepo(val flow: MutableStateFlow<List<DailyPlanEntity>>) : DailyPlanRepository {
        override fun observe(date: LocalDate): Flow<DailyPlanEntity?> = MutableStateFlow(null)
        override suspend fun upsert(date: LocalDate, plannedTaskIds: List<String>, plannedMinutes: Int) {}
        override fun observeRange(start: LocalDate, end: LocalDate): Flow<List<DailyPlanEntity>> = flow
    }

    private fun session(date: LocalDate, minutes: Int) = FocusSessionEntity(
        id = "s", taskId = null, project = "Work",
        startedAt = Instant.parse("2026-06-18T09:00:00Z"), endedAt = Instant.parse("2026-06-18T09:30:00Z"),
        minutes = minutes, date = date, createdAt = Instant.parse("2026-06-18T09:00:00Z"),
        updatedAt = Instant.parse("2026-06-18T09:00:00Z"), changeSeq = 0L, deletedAt = null,
    )

    private fun plan(date: LocalDate) = DailyPlanEntity(
        date = date, committedAt = Instant.parse("2026-06-18T07:00:00Z"),
        plannedTaskIds = listOf("x"), plannedMinutes = 60, changeSeq = 0L, deletedAt = null,
    )

    private fun buildVm(
        auth: FakeAuth,
        tasks: FakeTaskRepo,
        focus: FakeFocusRepo,
        plans: FakeDailyPlanRepo,
        syncHolder: SyncStatusHolder,
        pendingCount: Flow<Int>,
        signOut: SignOutAction,
        scheduler: SyncScheduler,
    ) = YouViewModel(auth, tasks, focus, plans, syncHolder, pendingCount, StatsCalculator(clock), signOut, scheduler, clock)

    @Test fun `uiState assembles profile sync stats and clears loading`() = runTest(dispatcher) {
        val auth = FakeAuth(SessionState.Authenticated("u1", "info_qmindtech@gmail.com", "UTC"))
        val tasksFlow = MutableStateFlow(
            listOf(
                fakeTask("a", plannedDate = today, status = TaskStatus.Done,
                    completedAt = Instant.parse("2026-06-18T10:00:00Z")),
                fakeTask("b", plannedDate = today, status = TaskStatus.Planned),
            )
        )
        val tasks = FakeTaskRepo(all = tasksFlow)
        val focus = FakeFocusRepo(MutableStateFlow(listOf(session(today, 90))))
        val plans = FakeDailyPlanRepo(MutableStateFlow(listOf(plan(today), plan(today.minusDays(1)))))
        val holder = SyncStatusHolder().apply { set(SyncStatus.Idle) }
        val pendingCount = MutableStateFlow(0)
        val vm = buildVm(auth, tasks, focus, plans, holder, pendingCount, SignOutAction {}, FakeSyncScheduler())
        vm.uiState.test {
            val s = expectMostRecentItem()
            assertEquals(false, s.loading)
            assertEquals("Info Qmindtech", s.profile.displayName)
            assertEquals("IQ", s.profile.initials)
            assertEquals(0.5f, s.todayProgress, 0.0001f) // 1 of 2 today done
            assertEquals(1, s.doneThisWeek)
            assertEquals("1.5h", s.focusHoursLabel) // 90 min
            assertEquals(2, s.dayStreak)            // today + yesterday via plans
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `onSignOut delegates to the SignOutAction exactly once`() = runTest(dispatcher) {
        var calls = 0
        val auth = FakeAuth(SessionState.Authenticated("u1", "a@b.co", "UTC"))
        val vm = buildVm(
            auth, FakeTaskRepo(all = MutableStateFlow(emptyList())),
            FakeFocusRepo(MutableStateFlow(emptyList())), FakeDailyPlanRepo(MutableStateFlow(emptyList())),
            SyncStatusHolder(), MutableStateFlow(0),
            SignOutAction { calls++ }, FakeSyncScheduler(),
        )
        vm.onSignOut()
        assertEquals(1, calls)
    }

    @Test fun `onRetrySync nudges the scheduler`() = runTest(dispatcher) {
        val scheduler = FakeSyncScheduler()
        val vm = buildVm(
            FakeAuth(SessionState.Authenticated("u1", "a@b.co", "UTC")),
            FakeTaskRepo(all = MutableStateFlow(emptyList())),
            FakeFocusRepo(MutableStateFlow(emptyList())), FakeDailyPlanRepo(MutableStateFlow(emptyList())),
            SyncStatusHolder(), MutableStateFlow(0),
            SignOutAction {}, scheduler,
        )
        vm.onRetrySync()
        assertTrue(scheduler.expeditedCount >= 1)
    }

    private class FakeSyncScheduler : SyncScheduler {
        var expeditedCount = 0
        override fun requestExpeditedSync() { expeditedCount++ }
        override fun schedulePeriodic() {}
        override fun cancelAll() {}
    }
}
