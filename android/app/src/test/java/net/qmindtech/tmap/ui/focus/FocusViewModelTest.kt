package net.qmindtech.tmap.ui.focus

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeFocusSessionRepo
import net.qmindtech.tmap.testutil.FakeProjectRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import net.qmindtech.tmap.testutil.fakeTask
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FocusViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-06-21T09:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(
        taskRepo: FakeTaskRepo = FakeTaskRepo(),
        projectRepo: FakeProjectRepo = FakeProjectRepo(),
    ): FocusViewModel {
        val controller = FocusController(FakeFocusSessionRepo(), taskRepo, clock, testDispatcher)
        return FocusViewModel(controller, taskRepo, projectRepo, clock)
    }

    @Test
    fun `start maps controller state to a Running ui state with mmss labels`() = runTest(testDispatcher) {
        val v = vm()
        v.uiState.test {
            awaitItem() // initial Idle frame
            v.start(taskId = null, project = "Reading", lengthMin = 25)
            runCurrent()
            val s = expectMostRecentItem()
            assertEquals(FocusPhase.Running, s.phase)
            assertEquals("Reading", s.project)
            assertEquals("25:00", s.remainingLabel)
            assertEquals("of 25:00", s.ofLabel)
            assertEquals(0f, s.progress, 0.0001f)
            v.end()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `progress advances as the timer ticks`() = runTest(testDispatcher) {
        val v = vm()
        v.start(taskId = null, project = "Reading", lengthMin = 1)
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        val s = v.uiState.value
        assertEquals("00:30", s.remainingLabel)
        assertEquals(0.5f, s.progress, 0.02f)
        v.end()
    }

    @Test
    fun `advance starts the next queued task and reports the queued count`() = runTest(testDispatcher) {
        val taskRepo = FakeTaskRepo()
        taskRepo.setSingle(fakeTask(id = "n1", title = "Next task"))
        val v = vm(taskRepo = taskRepo)
        v.start(taskId = "first", project = "Work", lengthMin = 25, queue = listOf("n1", "n2"))
        runCurrent()
        assertEquals(2, v.uiState.value.queuedCount)
        v.advance() // pop n1, start it
        runCurrent()
        assertEquals("n1", v.currentTaskIdForTest())
        assertEquals(1, v.uiState.value.queuedCount)
        v.end()
    }
}
