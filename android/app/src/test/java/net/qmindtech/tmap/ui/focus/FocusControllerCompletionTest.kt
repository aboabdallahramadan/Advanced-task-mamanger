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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FocusControllerCompletionTest {

    private val testDispatcher = StandardTestDispatcher()
    private val clock = FixedClock(Instant.parse("2026-06-21T09:00:00Z"))

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `a task-bound interval writes one FocusSession and adds the task time`() = runTest(testDispatcher) {
        val focus = FakeFocusSessionRepo()
        val tasks = FakeTaskRepo()
        val c = FocusController(focus, tasks, clock, testDispatcher)
        c.start(taskId = "t1", project = "Work", lengthMin = 1)
        runCurrent()
        advanceTimeBy(60_000) // run the full minute
        runCurrent()
        assertEquals(FocusPhase.Completed, c.state.value.phase)
        assertEquals(1, c.state.value.completedSessions)
        assertEquals(1, focus.created.size)
        val s = focus.created.single()
        assertEquals("t1", s.taskId)
        assertEquals("Work", s.project)
        assertEquals(1, s.minutes)
        assertEquals(LocalDate.parse("2026-06-21"), s.date)
        assertEquals(listOf("t1" to 1), tasks.actualTimeAdds)
    }

    @Test
    fun `a task-less interval writes a FocusSession but does not add task time`() = runTest(testDispatcher) {
        val focus = FakeFocusSessionRepo()
        val tasks = FakeTaskRepo()
        val c = FocusController(focus, tasks, clock, testDispatcher)
        c.start(taskId = null, project = "Reading", lengthMin = 1)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, focus.created.size)
        assertEquals(null, focus.created.single().taskId)
        assertTrue("task-less focus must not add actual time", tasks.actualTimeAdds.isEmpty())
    }

    @Test
    fun `completedSessions accumulates across consecutive intervals on the same controller`() =
        runTest(testDispatcher) {
            val c = FocusController(FakeFocusSessionRepo(), FakeTaskRepo(), clock, testDispatcher)
            c.start(taskId = "t1", project = "Work", lengthMin = 1)
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(1, c.state.value.completedSessions)
            c.start(taskId = "t1", project = "Work", lengthMin = 1)
            runCurrent(); advanceTimeBy(60_000); runCurrent()
            assertEquals(2, c.state.value.completedSessions)
        }
}
