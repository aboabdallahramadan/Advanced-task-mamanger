package net.qmindtech.tmap.ui.focus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.qmindtech.tmap.testutil.FakeFocusSessionRepo
import net.qmindtech.tmap.testutil.FakeTaskRepo
import net.qmindtech.tmap.testutil.FixedClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class FocusControllerStartTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-06-21T09:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun controller(focus: FakeFocusSessionRepo = FakeFocusSessionRepo(), tasks: FakeTaskRepo = FakeTaskRepo()) =
        FocusController(focus, tasks, clock, testDispatcher)

    @Test
    fun `start sets Running with full remaining seconds`() = runTest(testDispatcher) {
        val c = controller()
        c.start(taskId = "t1", project = "Work", lengthMin = 25)
        runCurrent()
        val s = c.state.value
        assertEquals(FocusPhase.Running, s.phase)
        assertEquals("t1", s.taskId)
        assertEquals("Work", s.project)
        assertEquals(25, s.lengthMin)
        assertEquals(25 * 60, s.remainingSeconds)
    }

    @Test
    fun `the ticker decrements one second per second on the injected dispatcher`() = runTest(testDispatcher) {
        val c = controller()
        c.start(taskId = null, project = "Reading", lengthMin = 1)
        runCurrent()
        assertEquals(60, c.state.value.remainingSeconds)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(59, c.state.value.remainingSeconds)
        advanceTimeBy(9_000)
        runCurrent()
        assertEquals(50, c.state.value.remainingSeconds)
        c.end() // stop the job so runTest does not hang on the remaining ticks
    }

    @Test
    fun `restart begins a fresh interval and cancels the previous ticker`() = runTest(testDispatcher) {
        val c = controller()
        c.start(taskId = "t1", project = "A", lengthMin = 5)
        runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(5 * 60 - 2, c.state.value.remainingSeconds)
        c.start(taskId = "t2", project = "B", lengthMin = 10) // fresh interval
        runCurrent()
        assertEquals("t2", c.state.value.taskId)
        assertEquals(10 * 60, c.state.value.remainingSeconds)
        c.end()
    }
}
