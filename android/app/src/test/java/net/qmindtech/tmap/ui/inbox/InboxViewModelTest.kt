package net.qmindtech.tmap.ui.inbox

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.data.local.TaskStatus
import net.qmindtech.tmap.data.repository.TaskEdit
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModelTest {
    private val today = LocalDate.of(2026, 6, 25)
    private val clock = FixedClock(Instant.parse("2026-06-25T08:00:00Z"))
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(): Pair<InboxViewModel, FakeTaskRepo> {
        val taskRepo = FakeTaskRepo()
        val projectRepo = FakeProjectRepo()
        val vm = InboxViewModel(taskRepo, projectRepo, clock)
        return vm to taskRepo
    }

    @Test fun observes_inbox_tasks_and_count() = runTest {
        val (vm, repo) = makeVm()
        repo.setByStatus(
            listOf(
                fakeTask(id = "t1", status = TaskStatus.Inbox),
                fakeTask(id = "t2", status = TaskStatus.Inbox),
            )
        )
        vm.uiState.test {
            val s = expectMostRecentItem()
            assertEquals(false, s.loading)
            assertEquals(2, s.tasks.size)
            assertEquals(2, s.count)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun schedule_delegates_update_with_planned_status_and_today() = runTest {
        val (vm, repo) = makeVm()
        vm.schedule("t1")
        assertEquals(
            listOf("t1" to TaskEdit(status = TaskStatus.Planned, plannedDate = today)),
            repo.updated,
        )
    }

    @Test fun backlog_delegates_update_with_backlog_status() = runTest {
        val (vm, repo) = makeVm()
        vm.backlog("t1")
        assertEquals(
            listOf("t1" to TaskEdit(status = TaskStatus.Backlog)),
            repo.updated,
        )
    }

    @Test fun assignProject_delegates_update_with_projectId() = runTest {
        val (vm, repo) = makeVm()
        vm.assignProject("t1", "p42")
        assertEquals(
            listOf("t1" to TaskEdit(projectId = "p42")),
            repo.updated,
        )
    }

    @Test fun delete_delegates_to_repo_delete() = runTest {
        val (vm, repo) = makeVm()
        vm.delete("t1")
        assertEquals(listOf("t1"), repo.deleted)
    }
}
